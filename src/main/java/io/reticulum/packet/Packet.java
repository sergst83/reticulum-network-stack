package io.reticulum.packet;

import io.reticulum.destination.Destination;
import io.reticulum.destination.ProofDestination;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.link.Link;
import lombok.Data;

/**
 *
 */
@Data
public class Packet {

    private PacketType packetType;
    private PacketContextType context;
    private byte[] destinationHash;
    private byte[] data;

    private Integer rssi;
    private Integer snr;
    private byte[] transportId;
    private int hops;
    private ConnectionInterface receivingInterface;
    private byte[] packetHash;
    private Object destination;
    private byte[] raw;
    private byte[] plaintext;
    public Packet(Object destination, byte[] proofData, PacketType proof, ConnectionInterface attachedInterface) {
    }

    public Packet(Link link, byte[] proofData, PacketType packetType, PacketContextType contextType) {

    }

    public Packet(Destination destination, byte[] announceData, PacketType announce, PacketContextType announceContext, ConnectionInterface attachedInterface) {

    }

    public Packet(Destination destination, byte[] requestData, PacketType linkRequest) {

    }

    public Packet(Link link, byte[] proofData, PacketType packetType) {

    }

    public Packet(Link link, byte[] data, PacketContextType packetContextType) {

    }

    public byte[] getHash() {
        return new byte[0];
    }

    public ProofDestination generatrProofDestination() {
        return null;
    }

    public PacketReceipt send() {
        return null;
    }

    public void pack() {

    }

    public byte[] getTruncatedHash() {
        return null;
    }

    public void prove() {

    }
}
