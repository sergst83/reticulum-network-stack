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

    public int read() {
        int result = -1;

        try {
            result = reader.read();
        } catch (IOException e) {
            log.error("Failed to read from channel");
        }
        return result;
    }

    public void write(byte[] b, int off, int len) throws IOException {
        writer.write(b, off, len);
    }

}
