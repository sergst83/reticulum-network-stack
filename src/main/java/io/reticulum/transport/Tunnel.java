package io.reticulum.transport;

import io.reticulum.interfaces.ConnectionInterface;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class Tunnel {
    private byte[] tunnelId; //0
    private ConnectionInterface anInterface; // 1
    private Map<String, Hops> tunnelPaths; //2
    private Instant expires; //3

    public ConnectionInterface getInterface() {
        return this.anInterface;
    }
}
