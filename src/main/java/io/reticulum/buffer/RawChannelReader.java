package io.reticulum.buffer;

import io.reticulum.channel.Channel;
import io.reticulum.message.MessageBase;
import io.reticulum.message.StreamDataMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

@Slf4j
public class RawChannelReader extends InputStream {
    /**
     * Hard cap on the inbound buffer per stream. When exceeded we drop further
     * incoming data and mark the stream EOF so consumers stop. Without this cap
     * a misbehaving (or simply faster-than-consumer) peer can drive the buffer
     * to OutOfMemoryError, which has been observed on public-facing nodes and
     * propagates up into {@code Transport.inbound()}.
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

    // Chunked buffer: incoming StreamDataMessage payloads are appended to the
    // deque by reference (zero-copy). Reads drain from the head chunk, tracked
    // by headOffset; a chunk is removed from the deque once fully consumed.
    //
    // Invariants:
    //   totalBuffered == sum of chunk.length for all chunk in chunks
    //   0 <= headOffset < chunks.peekFirst().length   (when chunks non-empty)
    //   headOffset == 0                                (when chunks empty)
    //   bufferedBytes() == totalBuffered - headOffset
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private int totalBuffered;
    private int headOffset;

    private boolean eof;
    private boolean overflowed;

    public RawChannelReader(int streamId, Channel channel) {
        this.streamId = streamId;
        this.channel = channel;
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

    /** Bytes still readable. Caller must hold {@code lock}. */
    private int bufferedBytes() {
        return totalBuffered - headOffset;
    }

    private Boolean handleMessage(MessageBase message) {
        if (message instanceof StreamDataMessage) {
            StreamDataMessage streamMessage = (StreamDataMessage) message;
            if (streamMessage.getStreamId().equals(this.streamId)) {
                lock.lock();
                try {
                    byte[] data = streamMessage.getData();
                    if (data != null && data.length > 0 && !overflowed) {
                        // Long arithmetic to avoid int overflow if MAX_BUFFER_SIZE is raised
                        // via -Dio.reticulum.buffer.maxSize to something close to Integer.MAX_VALUE.
                        long projected = (long) bufferedBytes() + (long) data.length;
                        if (projected > MAX_BUFFER_SIZE) {
                            overflowed = true;
                            eof = true;
                            log.warn("RawChannelReader buffer cap exceeded "
                                            + "(streamId={}, current={} bytes, incoming={} bytes, cap={} bytes) "
                                            + "— dropping data and signalling EOF to consumer",
                                    streamId, bufferedBytes(), data.length, MAX_BUFFER_SIZE);
                        } else {
                            // Zero-copy append: keep the reference, no reallocation.
                            chunks.addLast(data);
                            totalBuffered += data.length;
                        }
                    }
                    if (streamMessage.getEof()) {
                        eof = true;
                    }

                    int readable = bufferedBytes();
                    for (Consumer<Integer> listener : listeners) {
                        new Thread(() -> listener.accept(readable)).start();
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
            if (bufferedBytes() == 0) {
                return -1; // includes the EOF case
            }
            byte[] head = chunks.peekFirst();
            int b = head[headOffset++] & 0xFF;
            if (headOffset >= head.length) {
                chunks.removeFirst();
                totalBuffered -= head.length;
                headOffset = 0;
            }
            return b;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Read up to {@code readyBytes} bytes. Returned array is exactly the number
     * of bytes actually drained (may be shorter than requested if the buffer
     * holds fewer bytes — guards against the caller's ready-callback racing
     * with consumer state).
     *
     * @param readyBytes the amount the caller expects to be ready
     * @return a freshly allocated array containing the drained bytes
     */
    public byte[] read(Integer readyBytes) throws IOException {
        lock.lock();
        try {
            int want = Math.min(readyBytes, bufferedBytes());
            byte[] result = new byte[want];
            int copied = 0;
            while (copied < want) {
                byte[] head = chunks.peekFirst();
                int avail = head.length - headOffset;
                int take = Math.min(avail, want - copied);
                System.arraycopy(head, headOffset, result, copied, take);
                copied += take;
                headOffset += take;
                if (headOffset >= head.length) {
                    chunks.removeFirst();
                    totalBuffered -= head.length;
                    headOffset = 0;
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int available() {
        lock.lock();
        try {
            return bufferedBytes();
        } finally {
            lock.unlock();
        }
    }

    public void flush() {
        log.debug("reater - flushing buffer");
        lock.lock();
        try {
            chunks.clear();
            totalBuffered = 0;
            headOffset = 0;
            overflowed = false;
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
            chunks.clear();
            totalBuffered = 0;
            headOffset = 0;
        } finally {
            lock.unlock();
        }
    }
}
