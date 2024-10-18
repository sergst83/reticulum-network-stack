package io.reticulum.buffer;

import java.io.IOException;
//import java.io.InputStream;
import java.io.OutputStream;
//import java.util.ArrayList;
//import java.util.List;
import java.util.Arrays;
//import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
//import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import lombok.extern.slf4j.Slf4j;
//import io.netty.channel.ChannelException;
//import io.reticulum.channel.RChannelException;
import io.reticulum.channel.Channel;
//import io.reticulum.message.MessageBase;
import io.reticulum.message.StreamDataMessage;

@Slf4j
public class RawChannelWriter extends OutputStream {
    private static final int MAX_CHUNK_LEN = 1024 * 16;
    private static final int COMPRESSION_TRIES = 4;

    private final int streamId;
    private final Channel channel;
    private boolean eof = false;

    public RawChannelWriter(int streamId, Channel channel) {
        this.streamId = streamId;
        this.channel = channel;
    }

    // TODO: how to satisfy implement this in a meaningful way
    @Override
    public void write(int b) {
    //    byte[] bytes = {b};
    //    write(bytes, 0, bytes.length);
    }

    public void write(byte[] b) throws IOException {
        //log.info("TRACE-1 - writing buffer: {}", b);
        write(b, 0, b.length);
        //try {
        //    write(b, 0, b.length);
        //} catch (IOException e) {
        //    log.error("Channel: Error writing buffer.", e);
        //}
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        //log.info("TRACE-2 - writing buffer: {}", b);
        try {
            int compTries = COMPRESSION_TRIES;
            int compTry = 1;
            boolean compSuccess = false;
            int chunkLen = len;
            byte[] chunk = Arrays.copyOfRange(b, off, off + len);
            if (chunkLen > MAX_CHUNK_LEN) {
                chunkLen = MAX_CHUNK_LEN;
                chunk = Arrays.copyOfRange(b, off, off + MAX_CHUNK_LEN);
            }
            byte[] compressedChunk = null;
            while (chunkLen > 32 && compTry < compTries) {
                int chunkSegmentLength = chunkLen / compTry;
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                BZip2CompressorOutputStream bzip2OutputStream = new BZip2CompressorOutputStream(byteArrayOutputStream);
                bzip2OutputStream.write(chunk, 0, chunkSegmentLength);
                bzip2OutputStream.close();
                compressedChunk = byteArrayOutputStream.toByteArray();
                if (compressedChunk.length < StreamDataMessage.MAX_DATA_LEN && compressedChunk.length < chunkSegmentLength) {
                    compSuccess = true;
                    break;
                } else {
                    compTry++;
                }
            }

            if (compSuccess) {
                chunk = compressedChunk;
            } else {
                chunk = Arrays.copyOfRange(b, off, off + StreamDataMessage.MAX_DATA_LEN);
            }

            StreamDataMessage message = new StreamDataMessage(streamId, chunk, eof, compSuccess);
            channel.send(message);
        } catch (IOException e) {
            log.error("Channel: Error writing buffer.", e);
        }
    }

    public void flush() throws IOException {
        log.debug("writer - flushing buffer (currenty doesn't do anything)");
        //write(new byte[0], 0, 0);
    }

    public Boolean seekable() {
        return false;
    }

    public Boolean readable() { 
        return false;
    }

    public Boolean writable() {
        return true;
    }

    public void close() throws IOException {
        eof = true;
        write(new byte[0], 0, 0);
    }
}
