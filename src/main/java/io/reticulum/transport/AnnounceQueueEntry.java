package io.reticulum.transport;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AnnounceQueueEntry {
    private byte[] destination;
    private Instant time;
    private int hops;
    private long emitted;
    private byte[] raw;
}
