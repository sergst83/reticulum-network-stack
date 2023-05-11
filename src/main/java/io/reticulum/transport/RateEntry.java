package io.reticulum.transport;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class RateEntry {
    private Instant last;
    private Integer rateViolations;
    private Instant blockedUntil;
    private List<Instant> timestamps;
}
