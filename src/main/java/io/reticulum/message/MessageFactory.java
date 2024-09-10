package io.reticulum.message;

public final class MessageFactory {

    public static MessageBase getInstance(int msgType) {
        switch (msgType) {
            case 0x0101:
                return new StringMessage();
            case 0xabcd:
                return new MessageTest();
            case 0xf000:
                return new SystemMessage();
            case 0xff00:
                return new StreamDataMessage();
            default:
                throw new IllegalArgumentException("Unknown msgType " + msgType);
        }
    }
}
