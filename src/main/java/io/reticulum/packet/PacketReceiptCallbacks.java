package io.reticulum.packet;

import lombok.Data;

import java.util.function.Consumer;

@Data
public class PacketReceiptCallbacks {
    private Consumer<PacketReceipt> delivery;
}
