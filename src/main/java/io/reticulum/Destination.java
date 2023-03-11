package io.reticulum;

import lombok.Getter;

public class Destination {

    @Getter
    private Identity identity;

    @Getter
    private byte[] hash;
}
