package io.reticulum.channel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum RChannelExceptionType {
    ME_NO_MSG_Type(0),
    ME_INVALID_MSG_TYPE(1),
    ME_NOT_REGISTERED(2),
    ME_LINK_NOT_READY(3),
    ME_ALREADY_SENT(4),
    ME_TOO_BIG(5)
    ;
    
    private final int cEType;
}
