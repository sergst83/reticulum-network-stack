package io.reticulum.transport;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum TransportState {

    STATE_UNKNOWN((byte) 0x00),
    STATE_UNRESPONSIVE((byte) 0x01),
    STATE_RESPONSIVE((byte) 0x02),
    ;

    private final byte value;

    public static TransportState fromValue(final byte value) {
        return Arrays.stream(values())
                .filter(type -> type.getValue() == value)
                .findFirst()
                .orElseThrow();
    }
}
