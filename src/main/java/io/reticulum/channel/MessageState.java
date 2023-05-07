package io.reticulum.channel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MessageState {
    MSGSTATE_NEW(0),
    MSGSTATE_SENT(1),
    MSGSTATE_DELIVERED(2),
    MSGSTATE_FAILED(3),
    ;

    private final int value;
}
