package io.reticulum.link;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RequestReceiptStatus {

    FAILED((byte) 0x00),
    SENT((byte) 0x01),
    DELIVERED((byte) 0x02),
    RECEIVING((byte) 0x03),
    READY((byte) 0x04);

    private final byte value;
}
