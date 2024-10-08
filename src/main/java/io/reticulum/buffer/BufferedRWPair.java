package io.reticulum.buffer;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BufferedRWPair {

    private RawChannelReader reader;
    private RawChannelWriter writer;

    public BufferedRWPair(RawChannelReader reader, RawChannelWriter writer) {
        this.reader = reader;
        this.writer = writer;
    }

    public Integer read() {
        Integer result = -1;

        try {
            result = reader.read();
        } catch (IOException e) {
            log.error("Failed to read from channel {}", e);
        }
        return result;
    }

    public byte[] read(Integer readyBytes) {
        byte[] result = "".getBytes();

        try {
            result = reader.read(readyBytes);
            return result;
        } catch (IOException e) {
            log.error("Failed to read from channel, {}", e);
        }
        return result;
    }

    public void write(byte[] b, int off, int len) {
        try {
            writer.write(b, off, len);
        } catch (IOException e) {
            log.error("Failed to write to channel {}", e);
        }
    }

    public void write(byte[] b) {
        try {
            writer.write(b, 0, b.length);
        } catch (IOException e) {
            log.error("Failed to write to channel {}", e);
        }
    }

    public void flush() {
        reader.flush();
        try {
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to flush and writer ", e);
        }
    }

    public void close() {
        reader.close();
        try {
            writer.close();
        } catch (IOException e) {
            log.error("Failed to close writer ", e);
        }
    }

}
