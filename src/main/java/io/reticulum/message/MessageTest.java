package io.reticulum.message;

public class MessageTest extends MessageBase{
    @Override
    public Integer msgType() {
        //return 0xabcd;
        MessageType testType = MessageType.TEST;
        return testType.getMsgType();
    }

    @Override
    public byte[] pack() {
        return new byte[0];
    }

    @Override
    public void unpack(byte[] raw) {

    }
}
