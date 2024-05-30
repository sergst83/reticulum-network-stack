package io.reticulum.link;

import io.reticulum.identity.Identity;
import io.reticulum.packet.Packet;
import io.reticulum.resource.Resource;
import io.reticulum.resource.ResourceAdvertisement;
import lombok.Data;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

@Data
public class LinkCallbacks {
    Consumer<Link> linkEstablished;
    Consumer<Link> linkClosed;
    BiConsumer<byte[], Packet> packet;
    Function<ResourceAdvertisement, Boolean> resource;
    Consumer<Resource> resourceStarted;
    Consumer<Resource> resourceConcluded;
    BiConsumer<Link, Identity> remoteIdentified;
}
