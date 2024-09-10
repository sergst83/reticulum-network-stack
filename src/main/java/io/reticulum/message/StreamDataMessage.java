package io.reticulum.message;

import lombok.Data;

import static io.reticulum.constant.LinkConstant.MDU;

/**
 * This class is used to encapsulate binary stream data to be sent over a {@link io.reticulum.channel.Channel}.
 */
@Data
public class StreamDataMessage extends MessageBase {

    /**
     * When the Buffer package is imported, this value is calculated based on the value of OVERHEAD
     */
    private static final int MAX_DATA_LEN = MDU - 2 - 6;

    /**
     * The stream id is limited to 2 bytes - 2 bit
     */
    private static final int STREAM_ID_MAX = 0x3fff;  // 16383

    @Override
    public Integer msgType() {
        return 0xff00;
    }

    @Override
    public byte[] pack() {
        return new byte[0];
    }

    @Override
    public void unpack(byte[] raw) {

    }
}
