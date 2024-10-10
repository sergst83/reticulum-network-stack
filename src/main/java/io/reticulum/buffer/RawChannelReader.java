package io.reticulum.buffer;

//import java.io.CharConversionException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.concurrent.locks.ReentrantLock;

//import io.netty.channel.ChannelException;
import io.reticulum.channel.Channel;
//import io.reticulum.channel.RChannelException;
import io.reticulum.message.MessageBase;
import io.reticulum.message.StreamDataMessage;
import static io.reticulum.utils.IdentityUtils.concatArrays;
//import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
//import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import lombok.extern.slf4j.Slf4j;;

@Slf4j
public class RawChannelReader extends InputStream {
    private final int streamId;
    private final Channel channel;
    private final ReentrantLock lock = new ReentrantLock();
    //private final List<Callable<Integer>> listeners = new ArrayList<>();
    private List<Consumer<Integer>> listeners;
    private StreamDataMessage sdm = new StreamDataMessage();
    private byte[] buffer;
    private boolean eof;

    public RawChannelReader(int streamId, Channel channel) {
        this.streamId = streamId;
        this.channel = channel;
        this.buffer = new byte[0];
        this.eof = false;
        this.listeners = new ArrayList<>();
        //this.channel.registerMessageType(StreamDataMessage, true);
        try {
            this.channel.registerMessageType(sdm, true);
        } catch (Exception e) {
            log.error("Failed to register message type: {}", e);
        }
        this.channel.addMessageHandler(this::handleMessage);
    }

    public void addReadyCallback(Consumer<Integer> cb) {
        lock.lock();
        try {
            log.info("adding readyCallback: {}", cb);
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
                    
                    //Consumer<Integer> consumer = (Integer i) -> {};
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
                //flush();
                return result;
            }
            return -1;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param readyBytes
     * @return
     * @throws IOException
     * 
     * Read a certain number of bytes from a callback alerting to
     * a specific number of bytes being ready to be read.
     */
    public byte[] read(Integer readyBytes) throws IOException {
        lock.lock();
        try {
            byte[] result = Arrays.copyOfRange(buffer, 0, readyBytes);
            //flush();
            return result;
        } finally {
            lock.unlock();
        }
    }

    public void flush() {
        log.debug("reater - flushing buffer");
        this.buffer = new byte[0];
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
