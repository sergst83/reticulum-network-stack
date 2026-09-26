package io.reticulum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the bitrate-derived timeout helpers to Python RNS 1.5.2
 * ({@code RNS/Transport.py:3182-3208}).
 * <p>
 * Expected values were computed by running the reference formulas and
 * converting its float seconds to this implementation's milliseconds.
 */
class TransportTimeoutTest {

    /**
     * {@code per_bit_latency = 1/bitrate} was integer division here, yielding 0
     * for every bitrate above 1 bit/s. Every derived timeout therefore ignored
     * link speed and collapsed to a flat DEFAULT_PER_HOP_TIMEOUT — far too short
     * to establish a link over a slow radio interface.
     */
    @ParameterizedTest(name = "{0} bps -> {1} s/byte")
    @CsvSource({
            "1200,    0.006666666666666667",
            "9600,    0.0008333333333333334",
            "115200,  0.00006944444444444444",
            "1000000, 0.000008",
    })
    @DisplayName("Per-byte latency is real-valued, not integer-truncated to zero")
    void perByteLatencyIsNotTruncated(int bitrate, double expected) {
        var latency = Transport.perByteLatency(bitrate);

        assertTrue(latency > 0, "latency must not truncate to zero for " + bitrate + " bps");
        assertEquals(expected, latency, expected * 1e-9);
    }

    @Test
    @DisplayName("Per-byte latency is unknown for an absent or zero bitrate")
    void perByteLatencyUnknown() {
        assertNull(Transport.perByteLatency(null));
        assertNull(Transport.perByteLatency(0));
    }

    @ParameterizedTest(name = "{0} bps -> {1} ms")
    @CsvSource({
            "1200,    9333",
            "9600,    6417",
            "115200,  6035",
            "1000000, 6004",
    })
    @DisplayName("First hop timeout matches the reference")
    void firstHopTimeoutMatchesReference(int bitrate, int expectedMs) {
        assertEquals(expectedMs, Transport.firstHopTimeoutForLatency(Transport.perByteLatency(bitrate)));
    }

    @Test
    @DisplayName("First hop timeout falls back to the per-hop default when the bitrate is unknown")
    void firstHopTimeoutFallback() {
        assertEquals(6_000, Transport.firstHopTimeoutForLatency(null));
    }

    @ParameterizedTest(name = "{0} bps -> {1} ms")
    @CsvSource({
            "100,      86000",
            "1200,     12667",
            "9600,     6833",
            "115200,   6069",
            "1000000,  6008",
            "10000000, 6001",
    })
    @DisplayName("Medium path timeout matches the reference")
    void mediumPathTimeoutMatchesReference(int bitrate, long expectedMs) {
        assertEquals(expectedMs, Transport.mediumPathTimeout(bitrate));
    }

    /**
     * The reference floors the bitrate at MINIMUM_BITRATE, so anything slower
     * yields the same timeout as the floor itself.
     */
    @Test
    @DisplayName("Medium path timeout floors the bitrate at MINIMUM_BITRATE")
    void mediumPathTimeoutFloorsBitrate() {
        assertEquals(1_606_000L, Transport.mediumPathTimeout(5));
        assertEquals(Transport.mediumPathTimeout(5), Transport.mediumPathTimeout(1),
                "below the floor, the timeout must not keep growing");
    }

    @Test
    @DisplayName("Medium path timeout is zero when no bitrate is known")
    void mediumPathTimeoutUnknown() {
        assertEquals(0, Transport.mediumPathTimeout(null));
    }
}
