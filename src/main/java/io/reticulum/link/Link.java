package io.reticulum.link;

import io.reticulum.destination.Destination;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.packet.Packet;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Link {

    private int establishmentCost = 0;
    private String linkId;
    private ConnectionInterface attachedInterface;
    private long requestTime;
    private long lastInbound;

    public Link(Destination owner, byte[] peerPubBytes, byte[] peerSigPubBytes) {

    }

    public void teardown() {

    }

    public void setLinkId(Packet packet) {

    }

    public void setDestination(Object destination) {

    }

    public void establishmentTimeout(int timeout) {

    }

    public void addEstablishmentCost(int length) {
        establishmentCost += length;
    }

    public void handshake() {

    }

    public void prove() {

    }

    public void startWatchdog() {

    }
}
