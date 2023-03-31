package io.reticulum.link;

import io.reticulum.packet.Packet;
import lombok.Data;

import java.util.function.Consumer;

@Data
public class LinkCallbacks {
    Runnable linkEstablished;
    Consumer<Link> linkClosed;
    Packet packet;
    Object resource;
    Object resourceStarted;
    Object resourceConcluded;
    Object remoteIdentified;
}
