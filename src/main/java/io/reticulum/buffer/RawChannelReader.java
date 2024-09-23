package io.reticulum.buffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.locks.ReentrantLock;

import io.reticulum.channel.Channel;
import io.reticulum.channel.message.MessageBase;

public class RawChannelReader extends InputStream {
    private final int streamId;
    private final Channel channel;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Callback> listeners = new ArrayList<>();
    private byte[] buffer = new byte[0];
    private boolean eof = false;

    public RawChannelReader(int streamId, Channel channel) {
        this.streamId = streamId;
        this.channel = channel;
        this.channel.registerMessageType(StreamDataMessage.class, true);
        this.channel.addMessageHandler(this::handleMessage);
    }

    public void addReadyCallback(Callback cb) {
        lock.lock();
        try {
            listeners.add(cb);
        } finally {
            lock.unlock();
        }
    }

    public void removeReadyCallback(Callback cb) {
        lock.lock();
        try {
            listeners.remove(cb);
        } finally {
            lock.unlock();
        }
    }

    private void handleMessage(MessageBase message) {
        if (message instanceof StreamDataMessage) {
            StreamDataMessage streamMessage = (StreamDataMessage) message;
            if (streamMessage.streamId.equals(this.streamId)) {
                lock.lock();
                try {
                    if (streamMessage.data != null) {
                        buffer = concatenate(buffer, streamMessage.data);
                    }
                    if (streamMessage.eof) {
                        eof = true;
                    }
                    for (Callback listener : listeners) {
                        new Thread(() -> listener.call(buffer.length)).start();
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
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
