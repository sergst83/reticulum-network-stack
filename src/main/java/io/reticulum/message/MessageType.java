package io.reticulum.message;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum MessageType {
    STRING(0x0101),
    TEST(0xabcd),
    SYSTEM(0xf000),
    STREAM_DATA(0xff00)
    ;

    private final int msgType;
}
