/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.incubator.codec.quic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.DefaultChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.collection.LongObjectHashMap;
import io.netty.util.collection.LongObjectMap;
import io.netty.util.concurrent.Promise;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class QuicheQuicChannel extends AbstractChannel implements QuicChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private final long[] readableStreams = new long[1024];
    private final long[] writableStreams = new long[1024];
    private final LongObjectMap<QuicheQuicStreamChannel> streams = new LongObjectHashMap<>();
    private final InetSocketAddress remote;
    private final ChannelConfig config;
    private final boolean server;
    private final QuicCodec codec;
    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            if (connAddr != -1) {
                // Notify quiche there was a timeout.
                Quiche.quiche_conn_on_timeout(connAddr);
                writeAndFlushEgress();

                if (Quiche.quiche_conn_is_closed(connAddr)) {
                    unsafe().close(voidPromise());
                    Quiche.quiche_conn_free(connAddr);
                    connAddr = -1;
                } else {
                    // The connection is alive, reschedule.
                    timeoutFuture = null;
                    scheduleTimeout();
                }
            }
        }
    };
    private Future<?> timeoutFuture;
    private long connAddr;
    private boolean fireChannelReadCompletePending;
    private boolean writeEgressNeeded;

    private volatile boolean active = true;

    QuicheQuicChannel(QuicCodec codec, long connAddr, boolean server, Channel parent,
                      InetSocketAddress remote, ChannelHandler handler) {
        super(parent);
        this.codec = codec;
        config = new DefaultChannelConfig(this);
        this.server = server;
        this.connAddr = connAddr;
        this.remote = remote;
        pipeline().addLast(handler);
    }

    private void scheduleTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
        timeoutFuture = eventLoop().schedule(timeout,
                Quiche.quiche_conn_timeout_as_nanos(connAddr), TimeUnit.NANOSECONDS);
    }

    @Override
    protected DefaultChannelPipeline newChannelPipeline() {
        return new DefaultChannelPipeline(this) {
            @Override
            protected void onUnhandledInboundMessage(ChannelHandlerContext ctx, Object msg) {
                QuicheQuicStreamChannel channel = (QuicheQuicStreamChannel) msg;
                eventLoop().register(channel);
            }
        };
    }

    @Override
    public io.netty.util.concurrent.Future<QuicStreamChannel> newStream(boolean bidirectional) {
        return newStream(bidirectional, eventLoop().newPromise());
    }

    @Override
    public io.netty.util.concurrent.Future<QuicStreamChannel> newStream(
            boolean bidirectional, Promise<QuicStreamChannel> promise) {
        // TODO: implement me
        return promise.setFailure(new UnsupportedOperationException());
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new QuicChannelUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop eventLoop) {
        return parent().eventLoop() == eventLoop;
    }

    @Override
    protected SocketAddress localAddress0() {
        return parent().localAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remote;
    }

    @Override
    protected void doBind(SocketAddress socketAddress) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        active = false;
        Quiche.throwIfError(Quiche.quiche_conn_close(connAddr, false, 0,
                Unpooled.EMPTY_BUFFER.memoryAddress(), 0));
    }

    @Override
    protected void doBeginRead() throws Exception {
        // TODO: handle this
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer channelOutboundBuffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return active && connAddr != -1;
    }

    @Override
    public boolean isActive() {
        return isOpen();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    long connAddr() {
        return connAddr;
    }

    boolean freeIfClosed() {
        if (connAddr == -1) {
            return true;
        }
        if (Quiche.quiche_conn_is_closed(connAddr)) {
            Quiche.quiche_conn_free(connAddr);
            connAddr = -1;
            return true;
        }
        return false;
    }

    void shutdownRead(long streamId, ChannelPromise promise) {
        int res = Quiche.quiche_conn_stream_shutdown(
                connAddr, streamId, Quiche.QUICHE_SHUTDOWN_READ, 0);
        handleWriteEgress();
        Quiche.notifyPromise(res, promise);
    }

    void shutdownWrite(long streamId, ChannelPromise promise) {
        int res = Quiche.quiche_conn_stream_shutdown(
                connAddr, streamId, Quiche.QUICHE_SHUTDOWN_WRITE, 0);
        handleWriteEgress();
        Quiche.notifyPromise(res, promise);
    }

    void shutdownReadAndWrite(long streamId, ChannelPromise promise) {
        int res = Quiche.quiche_conn_stream_shutdown(
                connAddr, streamId, Quiche.QUICHE_SHUTDOWN_READ, 0) | Quiche.quiche_conn_stream_shutdown(
                connAddr, streamId, Quiche.QUICHE_SHUTDOWN_WRITE, 0);
        handleWriteEgress();
        Quiche.notifyPromise(res, promise);
    }

    boolean streamSendMultiple(long streamId, ChannelOutboundBuffer streamOutboundBuffer) throws Exception {
        boolean sendSomething = false;
        try {
            for (;;) {
                ByteBuf buffer = (ByteBuf) streamOutboundBuffer.current();
                if (buffer == null) {
                    break;
                }
                final int res;
                if (!buffer.hasMemoryAddress()) {
                    ByteBuf tmpBuffer = alloc().directBuffer(buffer.readableBytes());
                    try {
                        tmpBuffer.writeBytes(buffer, buffer.readerIndex(), buffer.readableBytes());
                        res = streamSend(streamId, tmpBuffer, false);
                    } finally {
                        tmpBuffer.release();
                    }
                } else {
                    res = streamSend(streamId, buffer, false);
                }

                if (Quiche.throwIfError(res)) {
                    // stream has no capacity left stop trying to send.
                    return false;
                }
                streamOutboundBuffer.removeBytes(res);
                sendSomething = true;
            }
            return true;
        } finally {
            if (sendSomething) {
                handleWriteEgress();
            }
        }
    }

    void streamClose(long streamId) throws Exception {
        try {
            // Just write an empty buffer and set fin to true.
            Quiche.throwIfError(streamSend(streamId, Unpooled.EMPTY_BUFFER, true));
        } finally {
            handleWriteEgress();
        }
    }

    private int streamSend(long streamId, ByteBuf buffer, boolean fin) {
        return Quiche.quiche_conn_stream_send(connAddr, streamId,
                buffer.memoryAddress() + buffer.readerIndex(), buffer.readableBytes(), fin);
    }

    boolean streamRecv(long streamId, ByteBuf buffer) throws Exception {
        int writerIndex = buffer.writerIndex();
        long memoryAddress = buffer.memoryAddress();
        int recvLen = Quiche.quiche_conn_stream_recv(connAddr, streamId,
                memoryAddress + writerIndex, buffer.writableBytes(), codec.finBuffer.memoryAddress());
        Quiche.throwIfError(recvLen);
        boolean fin = codec.finBuffer.getBoolean(writerIndex);
        // Skip the FIN
        buffer.setIndex(0, writerIndex + recvLen);
        return fin;
    }

    private void handleWriteEgress() {
        if (codec.inChannelRead) {
            writeEgressNeeded = true;
        } else {
            writeAndFlushEgress();
        }
    }

    /**
     * Receive some data on a quic connection. The given {@link ByteBuf} may be modified in place so if you depend on
     * the fact that the contents are not modified you will need to make a copy before.
     */
    void recv(ByteBuf buffer) {
        fireChannelReadCompletePending |= ((QuicChannelUnsafe) unsafe()).recvForConnection(buffer);
    }

    void flushStreams() {
        if (Quiche.quiche_conn_is_established(connAddr) || Quiche.quiche_conn_is_in_early_data(connAddr)) {
            int writable = Quiche.quiche_conn_writable(connAddr, writableStreams);
            System.err.println("writable streams " + writable);

            for (int i = 0; i < writable; i++) {
                long stream = writableStreams[writable];
                streams.get(stream).forceFlush();
            }
        }
    }

    boolean handleChannelReadComplete() {
        if (fireChannelReadCompletePending) {
            fireChannelReadCompletePending = false;
        }
        return writeEgress();
    }

    private void writeAndFlushEgress() {
        if (writeEgress()) {
            parent().flush();
        }
    }

    boolean writeEgress() {
        if (connAddr == -1) {
            return false;
        }
        if (!writeEgressNeeded) {
            return false;
        }
        writeEgressNeeded = false;
        boolean writeDone = false;
        for (;;) {
            ByteBuf out = parent().alloc().directBuffer(Quic.MAX_DATAGRAM_SIZE);
            int writerIndex = out.writerIndex();
            int written = Quiche.quiche_conn_send(connAddr, out.memoryAddress() + writerIndex, out.writableBytes());

            try {
                if (Quiche.throwIfError(written)) {
                    out.release();
                    break;
                }
            } catch (Exception e) {
                out.release();
                // TODO: is this correct ?
                parent().pipeline().fireExceptionCaught(e);
                break;
            }

            out.writerIndex(writerIndex + written);
            parent().write(new DatagramPacket(out, remote));
            writeDone = true;
        }
        if (writeDone) {
            // Schedule timeout.
            // See https://docs.rs/quiche/0.6.0/quiche/#generating-outgoing-packets
            scheduleTimeout();
        }
        return writeDone;
    }

    private final class QuicChannelUnsafe extends AbstractChannel.AbstractUnsafe {
        @Override
        public void connect(SocketAddress socketAddress, SocketAddress socketAddress1, ChannelPromise channelPromise) {
            // TODO: This needs some real implementation so its possible to create streams from an existing connection.
            //       This is true for client and server side as streams can be create on both in QUIC land.
            channelPromise.setFailure(new UnsupportedOperationException());
        }

        boolean recvForConnection(ByteBuf buffer) {
            int bufferReadable = buffer.readableBytes();
            int bufferReaderIndex = buffer.readerIndex();
            long memoryAddress = buffer.memoryAddress() + bufferReaderIndex;
            boolean fireChannelRead = false;
            writeEgressNeeded = true;
            try {
                do  {
                    // Call quiche_conn_recv(...) until we consumed all bytes or we did receive some error.
                    int res = Quiche.quiche_conn_recv(connAddr, memoryAddress, bufferReadable);
                    boolean done;
                    try {
                        done = Quiche.throwIfError(res);
                    } catch (Exception e) {
                        pipeline().fireExceptionCaught(e);
                        done = true;
                    }

                    fireChannelRead |= handleReadableStreams();
                    if (done) {
                        break;
                    } else {
                        memoryAddress += res;
                        bufferReadable -= res;
                    }
                } while (bufferReadable > 0);
                return fireChannelRead;
            } finally {
                buffer.skipBytes((int) (memoryAddress - buffer.memoryAddress()));
            }
        }

        // TODO: Improve the way how we handle channelReadComplete() for the streams to batch as many
        //       fireChannelRead(...) as possible per stream
        private boolean handleReadableStreams() {
            boolean fireChannelRead = false;
            if (Quiche.quiche_conn_is_established(connAddr) || Quiche.quiche_conn_is_in_early_data(connAddr)) {
                int readable = Quiche.quiche_conn_readable(connAddr, readableStreams);
                for (int i = 0; i < readable; i++) {
                    long streamId = readableStreams[i];
                    QuicheQuicStreamChannel streamChannel = streams.get(streamId);
                    if (streamChannel == null) {
                        streamChannel = new QuicheQuicStreamChannel(QuicheQuicChannel.this, streamId, server);
                        streams.put(streamId, streamChannel);
                        fireChannelRead = true;
                        pipeline().fireChannelRead(streamChannel);
                    } else {
                        streamChannel.recvIfPending();
                    }
                }
            }
            return fireChannelRead;
        }
    }
}
