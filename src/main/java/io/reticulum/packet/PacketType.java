package io.reticulum.packet;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PacketType {
    /**
     * Data packets
     */
    DATA((byte) 0x00),
    /**
     * Announces
     */
    ANNOUNCE((byte) 0x01),
    /**
     * Link requests
     */
    LINKREQUEST((byte) 0x02),
    /**
     * Proofs
     */
    PROOF((byte) 0x03),
    ;

    private final byte value;
}
