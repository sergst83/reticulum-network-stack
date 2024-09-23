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

import io.reticulum.channel.message.MessageBase;
import io.reticulum.channel.Channel;


public class Buffer {
    public static BufferedReader createReader(int streamId, Channel channel, Callback readyCallback) {
        RawChannelReader reader = new RawChannelReader(streamId, channel);
        if (readyCallback != null) {
            reader.addReadyCallback(readyCallback);
        }
        return new BufferedReader(reader);
    }

    public static BufferedWriter createWriter(int streamId, Channel channel) {
        RawChannelWriter writer = new RawChannelWriter(streamId, channel);
        return new BufferedWriter(writer);
    }

    public static BufferedRWPair createBidirectionalBuffer(int receiveStreamId, int sendStreamId, Channel channel, Callback readyCallback) {
        RawChannelReader reader = new RawChannelReader(receiveStreamId, channel);
        if (readyCallback != null) {
            reader.addReadyCallback(readyCallback);
        }
        RawChannelWriter writer = new RawChannelWriter(sendStreamId, channel);
        return new BufferedRWPair(reader, writer);
    }
}

