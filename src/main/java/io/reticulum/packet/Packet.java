package io.reticulum.packet;

import io.reticulum.destination.Destination;
import io.reticulum.destination.ProofDestination;
import io.reticulum.interfaces.ConnectionInterface;
import lombok.Getter;

/**
 *
 */
@Getter
public class Packet {

    private PacketType packetType;
    private byte[] destinationHash;
    private byte[] data;

    private Integer rssi;
    private Integer snr;
    private byte[] transportId;
    private int hops;
    private ConnectionInterface receivingInterface;
    private byte[] packetHash;

    public Packet(Object destination, byte[] proofData, PacketType proof, ConnectionInterface attachedInterface) {
    }

    public Packet(Destination destination, byte[] announceData, PacketType announce, PacketContextType announceContext, ConnectionInterface attachedInterface) {

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
