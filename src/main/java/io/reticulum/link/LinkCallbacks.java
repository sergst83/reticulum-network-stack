package io.reticulum.link;

import io.reticulum.packet.Packet;
import lombok.Value;

@Value
public class LinkCallbacks {
    boolean linkEstablished;
    boolean linkClosed;
    Packet packet;
    Object resource;
    Object resourceStarted;
    Object resourceConcluded;
    Object remoteIdentified;
}
