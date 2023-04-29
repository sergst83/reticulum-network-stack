package io.reticulum.resource;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ResourceStatus {
    NONE((byte) 0x00),
    QUEUED((byte) 0x01),
    ADVERTISED((byte) 0x02),
    TRANSFERRING((byte) 0x03),
    AWAITING_PROOF((byte) 0x04),
    ASSEMBLING((byte) 0x05),
    COMPLETE((byte) 0x06),
    FAILED((byte) 0x07),
    CORRUPT((byte) 0x08),
    ;

    private final byte value;
}
