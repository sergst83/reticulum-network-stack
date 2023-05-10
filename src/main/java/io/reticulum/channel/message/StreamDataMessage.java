package io.reticulum.channel.message;

import lombok.Data;

public class StreamDataMessage extends MessageBase{=

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
