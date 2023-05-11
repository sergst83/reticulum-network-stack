package io.reticulum.packet;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum PacketType {
    /**
     * Data packets
     */
    DATA((byte) 0b00),
    /**
     * Announces
     */
    ANNOUNCE((byte) 0b01),
    /**
     * Link requests
     */
    LINKREQUEST((byte) 0b10),
    /**
     * Proofs
     */
    PROOF((byte) 0b11),
    ;

    private final byte value;

    public static PacketType fromValue(final byte value) {
        return Arrays.stream(values())
                .filter(packetType -> packetType.getValue() == value)
                .findFirst()
                .orElseThrow();
    }
}
