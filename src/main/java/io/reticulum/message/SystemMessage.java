package io.reticulum.message;

public class SystemMessage extends MessageBase{
    @Override
    public Integer msgType() {
        return 0xf000;
    }

    @Override
    public byte[] pack() {
        return new byte[0];
    }

    @Override
    public void unpack(byte[] raw) {

    }
}
