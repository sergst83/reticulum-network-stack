package io.reticulum.buffer;

//import java.io.IOException;
//import java.io.InputStream;
//import java.io.InputStreamReader;
//import java.io.OutputStreamWriter;
//import java.io.OutputStream;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Arrays;
import java.util.function.Consumer;
//import java.io.ByteArrayInputStream;
//import java.io.ByteArrayOutputStream;
//import java.io.BufferedInputStream;
//import java.io.BufferedOutputStream;
//import java.io.BufferedReader;
//import java.io.BufferedWriter;
//import java.util.concurrent.locks.ReentrantLock;

//import io.reticulum.message.MessageBase;
import io.reticulum.channel.Channel;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Buffer {
    public static RawChannelReader createReader(int streamId, Channel channel, Consumer<Integer> readyCallback) {
        RawChannelReader rawChannelReader = new RawChannelReader(streamId, channel);
        if (readyCallback != null) {
            rawChannelReader.addReadyCallback(readyCallback);
        }
        //InputStreamReader isr = new InputStreamReader(rawChannelReader);
        //return new BufferedReader(isr);
        return rawChannelReader;
    }

    public static RawChannelWriter createWriter(int streamId, Channel channel) {
        RawChannelWriter rawChannelWriter = new RawChannelWriter(streamId, channel);
        //OutputStreamWriter osw = new OutputStreamWriter(rawChannelWriter);
        //return new BufferedWriter(osw);
        return rawChannelWriter;
    }

    public static BufferedRWPair createBidirectionalBuffer(int receiveStreamId, int sendStreamId, Channel channel, Consumer<Integer> readyCallback) {
        RawChannelReader reader = new RawChannelReader(receiveStreamId, channel);
        //if (readyCallback != null) {
        //    log.info("adding reader readyCallback; {}", readyCallback);
        //    reader.addReadyCallback(readyCallback);
        //}
        RawChannelWriter writer = new RawChannelWriter(sendStreamId, channel);
        return new BufferedRWPair(reader, writer);
    }
}

