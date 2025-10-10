package io.reticulum.packet;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum ContextType {
    FLAG_UNSET((byte) 0x00),
    FLAG_SET((byte) 0x01);

    private final byte value;

    public static ContextType fromValue(final byte value) {
        return Arrays.stream(values())
                .filter(type -> type.getValue() == value)
                .findFirst()
                .orElseThrow();
    }
}
