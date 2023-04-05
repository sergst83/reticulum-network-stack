package io.reticulum.packet;

import lombok.Data;

import java.time.Instant;
import java.util.function.Consumer;

@Data
public class PacketReceipt {
    private long timeout;
    private byte[] truncatedHash;
    private Consumer<PacketReceipt> timeoutCallback;
    private PacketReceiptStatus status;
    private boolean proved;
    private Instant concludedAt;
    private PacketReceiptCallbacks callbacks;
}
