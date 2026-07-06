package io.reticulum.packet;

import lombok.Data;

import java.util.function.Consumer;

@Data
public class PacketReceiptCallbacks {
    // volatile: written by PacketReceipt.setDeliveryCallback / setTimeoutCallback
    // (non-synchronized to avoid an ABBA with Channel.lock — see PacketReceipt),
    // read by PacketReceipt's synchronized validate* methods when dispatching.
    // Reference writes are atomic per JLS; volatile guarantees the reader sees
    // the latest write without needing the PacketReceipt monitor for the write.
    private volatile Consumer<PacketReceipt> delivery;
    private volatile Consumer<PacketReceipt> timeout;
}
