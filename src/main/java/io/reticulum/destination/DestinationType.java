package io.reticulum.destination;


import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum DestinationType {
    SINGLE((byte) 0b00),
    GROUP((byte) 0b01),
    PLAIN((byte) 0b10),
    LINK((byte) 0b11);

    private final byte value;

    public static DestinationType fromValue(final byte value) {
        return Arrays.stream(values())
                .filter(type -> type.getValue() == value)
                .findFirst()
                .orElseThrow();
    }
}
