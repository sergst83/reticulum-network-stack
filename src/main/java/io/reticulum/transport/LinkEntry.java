package io.reticulum.transport;

import io.reticulum.interfaces.ConnectionInterface;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class LinkEntry {
    /**
     * Timestamp
     */
    private Instant timestamp; //0
    /**
     * Next-hop transport ID
     */
    private byte[] nextHop; //1
    /**
     * Next-hop interface
     */
    private ConnectionInterface nextHopInterface; //2
    /**
     * Remaining hops
     */
    private int remainingHops; //3
    /**
     * Received on interface
     */
    private ConnectionInterface receivingInterface; //4
    /**
     * Taken hops
     */
    private int hops; //5
    /**
     *  Original destination hash
     */
    private byte[] destinationHash; //6
    /**
     * Validated
     */
    private boolean validated; //7
    /**
     * Proof timeout timestamp
     */
    private Instant proofTimestamp; //8
}
