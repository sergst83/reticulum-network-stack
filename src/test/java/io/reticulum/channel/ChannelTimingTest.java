package io.reticulum.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.reticulum.constant.ChannelConstant.RTT_FAST;
import static io.reticulum.constant.ChannelConstant.RTT_MEDIUM;
import static io.reticulum.constant.ChannelConstant.RTT_SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Units and formula parity for the channel's timing, against {@code RNS/Channel.py}.
 * <p>
 * {@code RTT_FAST} 0.18, {@code RTT_MEDIUM} 0.75 and {@code RTT_SLOW} 1.45 are
 * transcribed from the reference in <em>seconds</em>, while the outlet reported
 * milliseconds. Every link therefore read as slower than {@code RTT_SLOW} — any
 * RTT above 1.45 ms, which is all of them — so the window stayed at 1 for the
 * channel's whole life and neither rate class was ever reached.
 */
class ChannelTimingTest {

    /** Mirrors {@code Channel._get_packet_timeout_time}, in seconds. */
    private static double referenceTimeoutSeconds(int tries, double rttSeconds, int inFlight) {
        return Math.pow(1.5, tries - 1) * Math.max(rttSeconds * 2.5, 0.025) * (inFlight + 1.5);
    }

    @Test
    @DisplayName("the rate thresholds classify realistic link RTTs, in seconds")
    void thresholdsAreSeconds() {
        // Loopback and LAN links measure single-digit to tens of milliseconds. As
        // seconds those are fast; as raw milliseconds every one lands above
        // RTT_SLOW and is treated as the slowest link the stack knows about.
        for (var rttMs : new long[]{2, 13, 40, 120}) {
            var rttSeconds = rttMs / 1000.0;
            assertTrue(rttSeconds < RTT_FAST,
                    rttMs + "ms must classify as fast, got " + rttSeconds + "s");
            assertTrue(rttMs > RTT_SLOW,
                    rttMs + "ms would have been misread as slower than RTT_SLOW");
        }

        assertTrue(1.8 > RTT_SLOW, "1.8s is a genuinely slow link");
        assertTrue(0.5 > RTT_FAST && 0.5 < RTT_MEDIUM, "0.5s sits in the medium class");
    }

    @Test
    @DisplayName("the retry timeout matches the reference formula at every try")
    void retryTimeoutMatchesReference() {
        for (var rtt : new double[]{0.0, 0.002, 0.040, 0.500, 1.8}) {
            for (var tries = 1; tries <= 5; tries++) {
                for (var inFlight : new int[]{0, 1, 10, 48}) {
                    assertEquals((long) (referenceTimeoutSeconds(tries, rtt, inFlight) * 1000),
                            Channel.packetTimeoutMillis(tries, rtt, inFlight),
                            "rtt=" + rtt + " tries=" + tries + " inFlight=" + inFlight);
                }
            }
        }
    }

    @Test
    @DisplayName("the retry timeout grows with the number of packets in flight")
    void retryTimeoutScalesWithQueueDepth() {
        // A packet queued behind a full window legitimately takes longer to be
        // proven. The old constant factor ignored queue depth, so on a busy
        // channel packets were declared timed out while merely waiting their
        // turn — and five of those tore the link down.
        var empty = Channel.packetTimeoutMillis(1, 0.040, 0);
        var ten = Channel.packetTimeoutMillis(1, 0.040, 10);
        var full = Channel.packetTimeoutMillis(1, 0.040, 48);

        assertTrue(ten > empty * 4, "empty=" + empty + "ms ten=" + ten + "ms");
        assertTrue(full > ten * 3, "ten=" + ten + "ms full=" + full + "ms");
    }

    @Test
    @DisplayName("a zero RTT still yields the reference's 25ms floor")
    void zeroRttUsesTheFloor() {
        // Before the first proof the link reports rtt 0; the reference floors the
        // allowance at 25ms rather than collapsing the timeout to nothing.
        assertEquals((long) (0.025 * 1.5 * 1000), Channel.packetTimeoutMillis(1, 0.0, 0));
    }
}
