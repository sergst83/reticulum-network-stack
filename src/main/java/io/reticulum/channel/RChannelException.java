package io.reticulum.channel;

import java.lang.Exception;

public class RChannelException extends Exception {
    RChannelExceptionType type;

    public RChannelException (RChannelExceptionType cEType, String errorMessage) {
        super();
        this.type = cEType;
    } 
}
