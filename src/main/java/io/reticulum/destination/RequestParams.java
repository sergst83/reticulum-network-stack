package io.reticulum.destination;

import io.reticulum.identity.Identity;
import lombok.Value;

@Value
public class RequestParams {
    String path;
    byte[] data;
    Object requestId;
    Object linkId;
    Identity remoteIdentity;
    Object requestedAt;
}
