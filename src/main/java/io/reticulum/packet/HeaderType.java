package io.reticulum.packet;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum HeaderType {
    /**
     * Normal header format
     */
    HEADER_1((byte) 0x00),
    /**
     * Header format used for packets in transport
     */
    HEADER_2((byte) 0x01);

    private final byte value;

    public static HeaderType fromValue(final byte value) {
        return Arrays.stream(values())
                .filter(type -> type.getValue() == value)
                .findFirst()
                .orElseThrow();
    }
}
