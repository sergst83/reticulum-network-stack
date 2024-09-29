package io.reticulum.buffer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.concurrent.locks.ReentrantLock;

import io.reticulum.channel.Channel;
import io.reticulum.message.MessageBase;
import io.reticulum.message.StreamDataMessage;
import static io.reticulum.utils.IdentityUtils.concatArrays;

public class RawChannelReader extends InputStream {
    private final int streamId;
    private final Channel channel;
    private final ReentrantLock lock = new ReentrantLock();
    //private final List<Callable<Integer>> listeners = new ArrayList<>();
    private final List<Consumer<Integer>> listeners = new ArrayList<>();
    private byte[] buffer = new byte[0];
    private boolean eof = false;

    public RawChannelReader(int streamId, Channel channel) {
        this.streamId = streamId;
        this.channel = channel;
        //this.channel.register_message_type(StreamDataMessage.class, true);
        this.channel.addMessageHandler(this::handleMessage);
    }

    public void addReadyCallback(Consumer<Integer> cb) {
        lock.lock();
        try {
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
                    if (streamMessage.getData() != null) {
                        buffer = concatArrays(buffer, streamMessage.getData());
                    }
                    if (streamMessage.getEof()) {
                        eof = true;
                    }
                    
                    //Consumer<Integer> consumer = (Integer i) -> {}
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
