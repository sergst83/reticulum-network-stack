package io.reticulum.transport;

import io.reticulum.interfaces.ConnectionInterface;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class PathRequestEntry {
    private byte[] destinationHash;
    private Instant timeout;
    private ConnectionInterface requestingInterface;
}
