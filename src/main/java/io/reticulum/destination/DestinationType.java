package io.reticulum.destination;


import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum DestinationType {
    SINGLE((byte) 0x00),
    GROUP((byte) 0x01),
    PLAIN((byte) 0x02),
    LINK((byte) 0x03);

    private final byte value;
}
