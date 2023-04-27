package io.reticulum.destination;


import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum DestinationType {
    SINGLE((byte) 0x00),
    GROUP((byte) 0x01),
    PLAIN((byte) 0x02),
    LINK((byte) 0x03);

    private final byte value;

    public static DestinationType fromValue(final byte value) {
        return Arrays.stream(values())
                .filter(type -> type.getValue() == value)
                .findFirst()
                .orElseThrow();
    }
}
