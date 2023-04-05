package io.reticulum.packet;

import io.reticulum.constant.ReticulumConstant;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.link.Link;
import io.reticulum.transport.TransportType;
import lombok.Data;

import java.time.Instant;

import static io.reticulum.packet.PacketContextType.LRPROOF;

/**
 *
 */
@Data
public class Packet {

    private Instant sentAt;
    private boolean fromPacked;
    private boolean createReceipt;
    private boolean sent;
    private boolean packed;
    private int flags;
    private PacketContextType context;
    private byte[] destinationHash;
    private byte[] data;

    private Integer rssi;
    private Integer snr;
    private byte[] transportId;
    private int hops;
    private ConnectionInterface attachedInterface;
    private ConnectionInterface receivingInterface;
    private byte[] packetHash;
    private Destination destination;
    private byte[] raw;
    private byte[] plaintext;
    private TransportType transportType = TransportType.BROADCAST;
    private HeaderType headerType = HeaderType.HEADER_1;
    private PacketType packetType = PacketType.DATA;
    private PacketReceipt receipt;
    private int mtu;

    public Packet(ConnectionInterface attachedInterface) {
        this.attachedInterface = attachedInterface;
        this.mtu = ReticulumConstant.MTU;
    }

    public Packet(Destination destination, byte[] data, PacketType packetType, PacketContextType context, ConnectionInterface attachedInterface, boolean createReceipt) {
        this(attachedInterface);
        this.packetType = packetType;
        this.destination = destination;
        this.context = context;
        this.data = data;
        this.flags = getPackedFlags();
        this.createReceipt = createReceipt;
    }

    public Packet(Destination destination, byte[] data, PacketType packetType, PacketContextType context, ConnectionInterface attachedInterface) {
        this(destination, data, packetType, context, attachedInterface, true);
    }

    public Packet(Destination destination, byte[] proofData, PacketType proof, ConnectionInterface attachedInterface) {
        this(destination, proofData, proof, null, attachedInterface);
    }

    public Packet(Destination destination, byte[] requestData, PacketType linkRequest) {
        this(destination, requestData, linkRequest, null);
    }


    public Packet(Link link, byte[] data, PacketType packetType, PacketContextType contextType) {
        this(null);
        this.raw = data;
        this.packed = true;
        this.fromPacked = true;
        this.createReceipt = false;
    }

    public Packet(Link link, byte[] data, PacketType packetType) {
        this(link, data, packetType, null);
    }

    public Packet(Link link, byte[] data, PacketContextType packetContextType) {
        this(link, data, null, packetContextType);
    }

    /**
     * Двоичные влаги в виде числа
     *
     * @return флаги в виде
     */
    private int getPackedFlags() {
        int packed_flags;
        if (context == LRPROOF) {
            packed_flags = (this.headerType.getValue() << 6) | (transportType.getValue() << 4) | DestinationType.LINK.getValue() | packetType.getValue();
        } else {
            packed_flags = (this.headerType.getValue() << 6) | (transportType.getValue() << 4) | (this.destination.getType().getValue() << 2) | packetType.getValue();
        }

        return packed_flags;
    }

    public void pack() {

    }

    public byte[] getHash() {
        return new byte[0];
    }

    public ProofDestination generatrProofDestination() {
        return new ProofDestination(this);
    }

    public PacketReceipt send() {
        return null;
    }

    public byte[] getTruncatedHash() {
        return null;
    }

    public void prove() {

    }
}
