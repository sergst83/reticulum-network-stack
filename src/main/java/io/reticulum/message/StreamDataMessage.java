package io.reticulum.message;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import io.reticulum.message.MessageType;
import static io.reticulum.constant.LinkConstant.MDU;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@NoArgsConstructor
public class StreamDataMessage extends MessageBase {

    public static final Integer MSGTYPE = MessageType.STREAM_DATA.getMsgType();
    public static final Integer STREAM_ID_MAX = 0x3fff; // 16383
    public static final Integer MAX_DATA_LEN = MDU - 2 - 6; // 2 for stream data message header, 6 for channel envelope
    
    private Integer streamId;
    private boolean compressed;
    private byte[] data;
    private boolean eof;

    public StreamDataMessage(Integer streamId, byte[] data, Boolean eof, Boolean compressed) {
        super();
        if (streamId != null && streamId > STREAM_ID_MAX) {
            throw new IllegalArgumentException("stream_id must be 0-16383");
        }
        this.streamId = streamId;
        this.compressed = compressed;
        this.data = data != null ? data : new byte[0];
        this.eof = eof;
    }

    @Override
    public Integer msgType() {
        MessageType messageType = MessageType.STREAM_DATA;
        log.info("message type: {} - {}", MessageType.STREAM_DATA, messageType.getMsgType());
        return messageType.getMsgType();
        //return MSGTYPE;
        //return 0xff00;
    }

    public Boolean getEof() {
        return eof;
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
                bzip2InputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
