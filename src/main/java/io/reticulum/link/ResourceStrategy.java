package io.reticulum.link;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ResourceStrategy {
    ACCEPT_NONE((byte) 0x00),
    ACCEPT_APP((byte) 0x01),
    ACCEPT_ALL((byte) 0x02);

    private final byte value;
}
