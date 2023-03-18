package io.reticulum.destination;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Direction {
    IN((byte) 0x11),
    OUT((byte) 0x12);

    private final byte value;
}
