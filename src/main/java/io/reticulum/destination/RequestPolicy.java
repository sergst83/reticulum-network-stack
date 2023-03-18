package io.reticulum.destination;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RequestPolicy {
    ALLOW_NONE((byte) 0x00),
    ALLOW_ALL((byte) 0x01),
    ALLOW_LIST((byte) 0x02);

    private final byte value;
}
