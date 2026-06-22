package io.reticulum.buffer;

import io.reticulum.channel.Channel;
import io.reticulum.message.MessageBase;
import io.reticulum.message.StreamDataMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static io.reticulum.utils.IdentityUtils.concatArrays;

//import io.netty.channel.ChannelException;
//import io.reticulum.channel.RChannelException;
;

@Slf4j
public class RawChannelReader extends InputStream {
    /**
     * Hard cap on the inbound buffer per stream. When exceeded we drop further
     * incoming data and mark the stream EOF so consumers stop. Without this cap
     * a misbehaving (or simply faster-than-consumer) peer can drive
     * {@code buffer = concatArrays(buffer, ...)} to OutOfMemoryError, which has
     * been observed on public-facing nodes and propagates up into
     * {@code Transport.inbound()}.
     *
     * Override via {@code -Dio.reticulum.buffer.maxSize=<bytes>}.
     */
    public static final int MAX_BUFFER_SIZE =
            Integer.getInteger("io.reticulum.buffer.maxSize", 8 * 1024 * 1024); // 8 MiB
    private final int streamId;
    private final Channel channel;
    private final ReentrantLock lock = new ReentrantLock();
    private List<Consumer<Integer>> listeners;
    private StreamDataMessage sdm = new StreamDataMessage();
    private byte[] buffer;
    private boolean eof;
    private boolean overflowed;

    public RawChannelReader(int streamId, Channel channel) {
        this.streamId = streamId;
        this.channel = channel;
        this.buffer = new byte[0];
        this.eof = false;
        this.listeners = new CopyOnWriteArrayList<>();
        try {
            this.channel.registerMessageType(sdm, true);
        } catch (Exception e) {
            log.error("Failed to register message type: {}", sdm.msgType(), e);
        }
        this.channel.addMessageHandler(this::handleMessage);
    }

    public void addReadyCallback(Consumer<Integer> cb) {
        lock.lock();
        try {
            log.debug("adding readyCallback: {}", cb);
            listeners.add(cb);
        } finally {
            lock.unlock();
        }
    }

    public void removeReadyCallback(Consumer<Integer> cb) {
        lock.lock();
        try {
            listeners.remove(cb);
        } finally {
            lock.unlock();
        }
    }

    private Boolean handleMessage(MessageBase message) {
        if (message instanceof StreamDataMessage) {
            StreamDataMessage streamMessage = (StreamDataMessage) message;
            if (streamMessage.getStreamId().equals(this.streamId)) {
                lock.lock();
                try {
                    byte[] data = streamMessage.getData();
                    if (data != null && !overflowed) {
                        // Guard against unbounded growth: if the next concat would push past
                        // MAX_BUFFER_SIZE, drop the incoming chunk, mark EOF and stop accepting
                        // further data on this stream. Use long arithmetic to avoid int overflow
                        // when buffer.length + data.length > Integer.MAX_VALUE.
                        long projected = (long) buffer.length + (long) data.length;
                        if (projected > MAX_BUFFER_SIZE) {
                            overflowed = true;
                            eof = true;
                            log.warn("RawChannelReader buffer cap exceeded "
                                            + "(streamId={}, current={} bytes, incoming={} bytes, cap={} bytes) "
                                            + "— dropping data and signalling EOF to consumer",
                                    streamId, buffer.length, data.length, MAX_BUFFER_SIZE);
                        } else {
                            buffer = concatArrays(buffer, data);
                        }
                    }
                    if (streamMessage.getEof()) {
                        eof = true;
                    }

                    for (Consumer<Integer> listener : listeners) {
                        //new Thread(() -> listener.call(buffer.length)).start();
                        new Thread(() -> listener.accept(buffer.length)).start();
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
        return false;
    }

    @Override
    public int read() throws IOException {
        lock.lock();
        try {
            if (buffer.length == 0 && eof) {
                return -1;
            }
            if (buffer.length > 0) {
                int result = buffer[0];
                buffer = Arrays.copyOfRange(buffer, 1, buffer.length);
                return result;
            }
            return -1;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param readyBytes - The amount that is expected
     * @return - A copy array.
     * @throws IOException
     * 
     * Read a certain number of bytes from a callback alerting to
     * a specific number of bytes being ready to be read.
     */
    public byte[] read(Integer readyBytes) throws IOException {
        lock.lock();
        try {
            var result = Arrays.copyOfRange(buffer, 0, readyBytes);
            buffer = Arrays.copyOfRange(buffer, readyBytes, buffer.length);
            return result;
        } finally {
            lock.unlock();
        }
    }

    public void flush() {
        log.debug("reater - flushing buffer");
        lock.lock();
        try {
            this.buffer = new byte[0];
            this.overflowed = false;
        } finally {
            lock.unlock();
        }
    }

    public Boolean seekable() {
        return false;
    }

    public Boolean writable() {
        return false;
    }

    public Boolean readable() { 
        return true;
    }

    public void close() {
        lock.lock();
        try {
            channel.removeMessageHandler(this::handleMessage);
            listeners.clear();
        } finally {
            lock.unlock();
        }
    }
}
