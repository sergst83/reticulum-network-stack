package io.reticulum;

import io.reticulum.interfaces.ConnectionInterface;
import lombok.Getter;

@Getter
public class Packet {

    private byte packetType;
    private byte[] destinationHash;
    private byte[] data;

    private Integer rssi;
    private Integer snr;
    private byte[] transportId;
    private int hops;
    private ConnectionInterface receivingInterface;
    private byte[] packetHash;

    public Packet(Object destination, byte[] proofData, byte proof, ConnectionInterface attachedInterface) {
    }

    public byte[] getHash() {
        return new byte[0];
    }

    public ProofDestination generatrProofDestination() {
        return null;
    }

    public void send() {

    }
}
