package io.reticulum.destination;

import io.reticulum.identity.Identity;
import lombok.Value;

import java.time.Instant;

@Value
public class Request {
    String path;
    byte[] data;
    byte[] requestId;
    byte[] linkId;
    Identity remoteIdentity;
    Instant requestedAt;
}
