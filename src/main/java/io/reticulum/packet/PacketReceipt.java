package io.reticulum.packet;

import lombok.Data;

import java.util.function.Consumer;

@Data
public class PacketReceipt {
    private long timeout;
    private byte[] truncatedHash;
    private Consumer<PacketReceipt> timeoutCallback;

    public void setTimeoutCallback(Consumer<PacketReceipt> callback) {
        timeoutCallback = callback;
    }
}
