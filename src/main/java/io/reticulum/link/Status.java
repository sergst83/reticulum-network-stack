package io.reticulum.link;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Status {
    PENDING((byte) 0x00),
    HANDSHAKE((byte) 0x01),
    ACTIVE((byte) 0x02),
    STALE((byte) 0x03),
    CLOSED((byte) 0x04);

    private final byte value;
}
