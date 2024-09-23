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
import java.util.zip.BZip2CompressorInputStream;
import java.util.zip.BZip2CompressorOutputStream;

import io.reticulum.channel.message.MessageBase;


public class StreamDataMessage extends MessageBase {
    public static final int MSGTYPE = SystemMessageTypes.SMT_STREAM_DATA;
    public static final int STREAM_ID_MAX = 0x3fff; // 16383
    public static final int MAX_DATA_LEN = RNS.Link.MDU - 2 - 6; // 2 for stream data message header, 6 for channel envelope

    private Integer streamId;
    private boolean compressed;
    private byte[] data;
    private boolean eof;

    public StreamDataMessage(Integer streamId, byte[] data, boolean eof, boolean compressed) {
        super();
        if (streamId != null && streamId > STREAM_ID_MAX) {
            throw new IllegalArgumentException("stream_id must be 0-16383");
        }
        this.streamId = streamId;
        this.compressed = compressed;
        this.data = data != null ? data : new byte[0];
        this.eof = eof;
    }

    public byte[] pack() {
        if (streamId == null) {
            throw new IllegalArgumentException("stream_id");
        }

        int headerVal = (0x3fff & streamId) | (eof ? 0x8000 : 0x0000) | (compressed ? 0x4000 : 0x0000);
        byte[] header = new byte[2];
        header[0] = (byte) (headerVal >> 8);
        header[1] = (byte) (headerVal);
        byte[] result = new byte[header.length + data.length];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(data, 0, result, header.length, data.length);
        return result;
    }

    public void unpack(byte[] raw) {
        streamId = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
        eof = (0x8000 & streamId) > 0;
        compressed = (0x4000 & streamId) > 0;
        streamId = streamId & 0x3fff;
        data = new byte[raw.length - 2];
        System.arraycopy(raw, 2, data, 0, data.length);

        if (compressed) {
            try {
                BZip2CompressorInputStream bzip2InputStream = new BZip2CompressorInputStream(new ByteArrayInputStream(data));
                data = bzip2InputStream.readAllBytes();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
