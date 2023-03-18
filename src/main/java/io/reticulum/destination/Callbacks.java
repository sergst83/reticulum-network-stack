package io.reticulum.destination;

import io.reticulum.Link;
import io.reticulum.packet.Packet;
import lombok.Data;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Data
public class Callbacks {

    private Consumer<Link> linkEstablished;
    private BiConsumer<byte[], Packet> packet;
    private Consumer<Packet> proofRequested;

}
