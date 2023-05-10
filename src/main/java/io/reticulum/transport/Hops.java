package io.reticulum.transport;

import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.packet.Packet;
import lombok.Data;

import java.time.Instant;

@Data
public class Hops {
    private Instant timestamp; //0
    private byte[] via; //1
    private int hops; //2
    private Instant expires; //3
    private byte[] randomBlobs;
    private ConnectionInterface anInterface; //5
    private Packet packet; //6

    public int getPathLength() {
        return hops;
    }

    public ConnectionInterface getInterface() {
        return anInterface;
    }
}
