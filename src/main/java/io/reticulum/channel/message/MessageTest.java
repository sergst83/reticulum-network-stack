package io.reticulum.channel.message;

public class MessageTest extends MessageBase{
    @Override
    public Integer msgType() {
        return 0xabcd;
    }

    @Override
    public byte[] pack() {
        return new byte[0];
    }

    @Override
    public void unpack(byte[] raw) {

    }
}
