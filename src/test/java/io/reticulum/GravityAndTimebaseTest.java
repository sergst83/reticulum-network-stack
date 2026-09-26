package io.reticulum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.reticulum.constant.TransportConstant.ALLOW_LINK_PATH_REBALANCE;
import static io.reticulum.constant.TransportConstant.DEFAULT_GRAVITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the announce emission timebase helpers that path selection depends on,
 * and the gravity/rebalancing constants.
 * <p>
 * The timebase comparison matters beyond gravity: the equal-or-fewer-hops branch
 * of path selection used only a "have I heard this blob before" check, so an
 * announce carrying a fresh blob but an <em>older</em> emission timestamp could
 * displace a newer path. The reference requires both
 * ({@code RNS/Transport.py:2237}).
 */
class GravityAndTimebaseTest {

    /** Builds a 10-byte random blob whose bytes 5..9 encode the given timestamp. */
    private static byte[] blob(long timebase) {
        var randomBlob = new byte[10];
        for (int i = 0; i < 5; i++) {
            randomBlob[i] = (byte) (0xA0 + i);
        }
        for (int i = 9; i >= 5; i--) {
            randomBlob[i] = (byte) (timebase & 0xFF);
            timebase >>= 8;
        }

        return randomBlob;
    }

    @Test
    @DisplayName("Timebase is the big-endian value of blob bytes 5..9")
    void timebaseDecoding() {
        assertEquals(0L, Transport.timebaseFromRandomBlob(blob(0)));
        assertEquals(1L, Transport.timebaseFromRandomBlob(blob(1)));
        assertEquals(1_757_000_000L, Transport.timebaseFromRandomBlob(blob(1_757_000_000L)));
        // Five bytes hold values well past any plausible epoch second
        assertEquals(0xFFFFFFFFFFL, Transport.timebaseFromRandomBlob(blob(0xFFFFFFFFFFL)));
    }

    @Test
    @DisplayName("Timebase decoding is unsigned")
    void timebaseIsUnsigned() {
        // A high byte with the sign bit set must not decode negative
        assertTrue(Transport.timebaseFromRandomBlob(blob(0x80_00000000L)) > 0);
        assertEquals(0x80_00000000L, Transport.timebaseFromRandomBlob(blob(0x80_00000000L)));
    }

    @Test
    @DisplayName("The timebase of a blob set is the most recent emission")
    void timebaseOfSet() {
        assertEquals(0L, Transport.timebaseFromRandomBlobs(List.of()));
        assertEquals(500L, Transport.timebaseFromRandomBlobs(List.of(blob(500))));
        assertEquals(900L, Transport.timebaseFromRandomBlobs(
                List.of(blob(100), blob(900), blob(400))));
        assertEquals(900L, Transport.timebaseFromRandomBlobs(
                List.of(blob(900), blob(100))), "order must not matter");
    }

    /**
     * The scenario the missing guard allowed: a blob never heard before, but
     * emitted earlier than the path currently held.
     */
    @Test
    @DisplayName("A fresh blob can still be older than the held path")
    void freshBlobCanBeStale() {
        var heldPath = List.of(blob(2000), blob(1500));
        var pathTimebase = Transport.timebaseFromRandomBlobs(heldPath);

        var staleAnnounce = Transport.timebaseFromRandomBlob(blob(1000));
        var freshAnnounce = Transport.timebaseFromRandomBlob(blob(3000));

        assertTrue(staleAnnounce < pathTimebase,
                "an unheard blob may still predate the path and must not displace it");
        assertTrue(freshAnnounce > pathTimebase,
                "a genuinely newer announce must be able to displace the path");
    }

    @Test
    @DisplayName("Gravity and rebalancing defaults match the reference")
    void defaults() {
        assertEquals(0, DEFAULT_GRAVITY);
        assertTrue(ALLOW_LINK_PATH_REBALANCE);
    }
}
