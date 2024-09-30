package io.reticulum.buffer;

//import java.io.IOException;
//import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
//import java.io.OutputStream;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Arrays;
import java.util.function.Consumer;
//import java.io.ByteArrayInputStream;
//import java.io.ByteArrayOutputStream;
//import java.io.BufferedInputStream;
//import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
//import java.util.concurrent.locks.ReentrantLock;

//import io.reticulum.message.MessageBase;
import io.reticulum.channel.Channel;


public class Buffer {
    public static BufferedReader createReader(int streamId, Channel channel, Consumer<Integer> readyCallback) {
        RawChannelReader rcr = new RawChannelReader(streamId, channel);
        if (readyCallback != null) {
            rcr.addReadyCallback(readyCallback);
        }
        InputStreamReader isr = new InputStreamReader(rcr);
        return new BufferedReader(isr);
    }

    public static BufferedWriter createWriter(int streamId, Channel channel) {
        RawChannelWriter rcw = new RawChannelWriter(streamId, channel);
        OutputStreamWriter osw = new OutputStreamWriter(rcw);
        return new BufferedWriter(osw);
    }

    public static BufferedRWPair createBidirectionalBuffer(int receiveStreamId, int sendStreamId, Channel channel, Consumer<Integer> readyCallback) {
        RawChannelReader reader = new RawChannelReader(receiveStreamId, channel);
        if (readyCallback != null) {
            reader.addReadyCallback(readyCallback);
        }
        RawChannelWriter writer = new RawChannelWriter(sendStreamId, channel);
        return new BufferedRWPair(reader, writer);
    }
}

