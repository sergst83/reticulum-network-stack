package io.reticulum.buffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.function.Consumer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.util.concurrent.locks.ReentrantLock;

import io.reticulum.message.MessageBase;
import io.reticulum.channel.Channel;


public class Buffer {
    public static BufferedInputStream createReader(int streamId, Channel channel, Consumer<Integer> readyCallback) {
        RawChannelReader reader = new RawChannelReader(streamId, channel);
        if (readyCallback != null) {
            reader.addReadyCallback(readyCallback);
        }
        return new BufferedInputStream(reader);
    }

    public static BufferedOutputStream createWriter(int streamId, Channel channel) {
        RawChannelWriter writer = new RawChannelWriter(streamId, channel);
        return new BufferedOutputStream(writer);
    }

    //// Note: We need an implementation of BufferedRWPair
    //public static BufferedRWPair createBidirectionalBuffer(int receiveStreamId, int sendStreamId, Channel channel, Consumer<Integer> readyCallback) {
    //    RawChannelReader reader = new RawChannelReader(receiveStreamId, channel);
    //    if (readyCallback != null) {
    //        reader.addReadyCallback(readyCallback);
    //    }
    //    RawChannelWriter writer = new RawChannelWriter(sendStreamId, channel);
    //    return new BufferedRWPair(reader, writer);
    //}
}

