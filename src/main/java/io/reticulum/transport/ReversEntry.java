package io.reticulum.transport;

import io.reticulum.interfaces.ConnectionInterface;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ReversEntry {
    /**
     * Received on interface
     */
    private ConnectionInterface receivingInterface; //0
    /**
     * Outbound interface
     */
    private ConnectionInterface outboundInterface; //1
    /**
     * Timestamp
     */
    private Instant timestamp; //2
}
