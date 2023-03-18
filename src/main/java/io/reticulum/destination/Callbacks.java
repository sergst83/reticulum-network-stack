package io.reticulum.destination;

import io.reticulum.packet.Packet;
import lombok.Data;

@Data
public class Callbacks {

    private Object linkEstablished;
    private Packet packet;
    private Object proofRequested;

}
