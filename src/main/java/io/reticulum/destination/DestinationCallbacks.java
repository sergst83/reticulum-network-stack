package io.reticulum.destination;

import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import lombok.Data;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

@Data
public class DestinationCallbacks {

    private Consumer<Link> linkEstablished;
    private BiConsumer<byte[], Packet> packet;
    private Function<Packet, Boolean> proofRequested;

}
