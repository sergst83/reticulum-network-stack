package io.reticulum.packet;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PacketReceiptStatus {

    FAILED((byte) 0x00),
    SENT((byte) 0x01),
    DELIVERED((byte) 0x02),
    CULLED((byte) 0xFF);

    private final byte value;
}
