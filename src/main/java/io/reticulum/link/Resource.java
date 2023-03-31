package io.reticulum.link;

import lombok.Data;

import java.io.InputStream;

@Data
public class Resource {
    private ResourceStatus status;
    private InputStream data;

    public Resource(byte[] packedRequest, Link link, byte[] requestId, boolean isResponse, long timeout) {

    }

    public void cancel() {

    }
}
