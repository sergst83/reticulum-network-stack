package io.reticulum.transport;

import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.packet.Packet;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AnnounceEntry {
    private Instant timestamp; //0
    private Instant retransmitTimeout; //1
    private int retries; //2
    private byte[] transportId; //3
    private int hops; //4
    private Packet packet; //5
    private int localRebroadcasts; //6
    private boolean blockRebroadcasts; //7
    private ConnectionInterface attachedInterface; //8
}
