package io.reticulum.link;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TeardownSession {
    TIMEOUT((byte) 0x01),
    INITIATOR_CLOSED((byte) 0x02),
    DESTINATION_CLOSED((byte) 0x03);

    private final byte value;
}
