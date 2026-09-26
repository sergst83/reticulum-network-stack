package io.reticulum.interfaces;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers announce and path request ingress control, and path request egress
 * control ({@code RNS/Interfaces/Interface.py:188-248}).
 * <p>
 * {@code shouldIngressLimit()} previously returned false unconditionally, so
 * announce ingress control never engaged at all despite all of its
 * configuration being present — an interface under an announce storm had no
 * protection. The path request variants did not exist.
 */
class IngressEgressControlTest {

    /** Minimal concrete interface; the control logic lives in the abstract base. */
    private static final class TestInterface extends AbstractConnectionInterface {
        @Override public void processIncoming(byte[] data) { }
        @Override public void processOutgoing(byte[] data) { }
        @Override public void launch() { }
        @Override public void run() { }

        /** Backdates the sample windows so a burst reads as sustained over a real span. */
        void seedAnnounces(int count, long spanSeconds) {
            seed(getIaFreqDeque(), count, spanSeconds);
        }

        void seedPathRequests(int count, long spanSeconds) {
            seed(getIpFreqDeque(), count, spanSeconds);
        }

        void seedSentPathRequests(int count, long spanSeconds) {
            seed(getOpFreqDeque(), count, spanSeconds);
        }

        private static void seed(java.util.List<Instant> deque, int count, long spanSeconds) {
            deque.clear();
            var oldest = Instant.now().minusSeconds(spanSeconds);
            // newest first, matching how the windows are maintained
            for (int i = 0; i < count; i++) {
                deque.add(oldest.plusSeconds((spanSeconds * i) / Math.max(1, count)));
            }
            deque.add(oldest);
        }
    }

    private TestInterface iface;

    @BeforeEach
    void setUp() {
        iface = new TestInterface();
        iface.setIngressControl(true);
    }

    @Test
    @DisplayName("Ingress control off means never limiting")
    void ingressControlDisabled() {
        iface.setIngressControl(false);
        iface.seedAnnounces(60, 1);
        iface.seedPathRequests(60, 1);

        assertFalse(iface.shouldIngressLimit());
        assertFalse(iface.shouldIngressLimitPr());
    }

    @Test
    @DisplayName("A quiet interface does not limit announces")
    void quietInterfaceDoesNotLimitAnnounces() {
        assertFalse(iface.shouldIngressLimit(), "no samples at all");

        // Well under the new-interface threshold of 3 Hz
        iface.seedAnnounces(4, 60);
        assertFalse(iface.shouldIngressLimit());
    }

    @Test
    @DisplayName("An announce burst engages ingress limiting and holds it")
    void announceBurstEngagesAndHolds() {
        // 60 announces across one second is far above the 3 Hz new-interface threshold
        iface.seedAnnounces(60, 1);

        assertTrue(iface.shouldIngressLimit(), "a burst must engage limiting");
        assertTrue(iface.isIcBurstActive());

        // Rate collapses, but the hold period has not elapsed
        iface.seedAnnounces(3, 600);
        assertTrue(iface.shouldIngressLimit(), "limiting holds for ic_burst_hold after the burst");
        assertTrue(iface.isIcBurstActive());
    }

    @Test
    @DisplayName("Announce limiting releases once the rate has been low for the hold period")
    void announceLimitReleasesAfterHold() {
        iface.seedAnnounces(60, 1);
        assertTrue(iface.shouldIngressLimit());

        // Backdate activation and sustain beyond ic_burst_hold (15 s)
        var wellPast = Instant.now().minusSeconds(iface.getIcBurstHold().longValue() + 5);
        iface.setIcBurstActivated(wellPast);
        iface.setIcBurstSustained(wellPast);
        iface.seedAnnounces(3, 600);

        assertTrue(iface.shouldIngressLimit(), "the releasing call still reports limited");
        assertFalse(iface.isIcBurstActive(), "but the burst state is cleared");
        assertFalse(iface.shouldIngressLimit(), "and the next call is unlimited");
    }

    @Test
    @DisplayName("A path request burst engages ingress limiting")
    void pathRequestBurstEngages() {
        assertFalse(iface.shouldIngressLimitPr(), "quiet interface");

        // Above the 3 Hz new-interface path request threshold
        iface.seedPathRequests(60, 1);

        assertTrue(iface.shouldIngressLimitPr());
        assertTrue(iface.isIcPrBurstActive());
        assertEquals(3, iface.getIcPrBurstCooldown(), "cooldown is armed on activation");
    }

    /**
     * The path request variant leaves the burst state via a cooldown counter
     * rather than the sample-count check the announce variant uses.
     */
    @Test
    @DisplayName("Path request limiting drains its cooldown before releasing")
    void pathRequestCooldownDrains() {
        iface.seedPathRequests(60, 1);
        assertTrue(iface.shouldIngressLimitPr());

        var wellPast = Instant.now().minusSeconds(iface.getIcBurstHold().longValue() + 5);
        iface.setIcPrBurstActivated(wellPast);
        iface.setIcPrBurstSustained(wellPast);
        iface.seedPathRequests(3, 600);

        // Three quiet evaluations drain the cooldown, the fourth clears the burst
        for (int i = 3; i > 0; i--) {
            assertTrue(iface.shouldIngressLimitPr(), "still limited while cooling down");
            assertEquals(i - 1, iface.getIcPrBurstCooldown());
            iface.setIcPrBurstActivated(wellPast);
            iface.setIcPrBurstSustained(wellPast);
        }

        assertTrue(iface.shouldIngressLimitPr(), "the releasing call still reports limited");
        assertFalse(iface.isIcPrBurstActive());
    }

    @Test
    @DisplayName("Egress control is off unless enabled")
    void egressControlDisabledByDefault() {
        iface.seedSentPathRequests(60, 1);

        assertFalse(iface.shouldEgressLimitPr(), "egress control defaults off, as in the reference");
    }

    @Test
    @DisplayName("Egress limiting engages above the configured rate")
    void egressLimitEngages() {
        iface.setEgressControl(true);

        iface.seedSentPathRequests(60, 1);
        assertTrue(iface.shouldEgressLimitPr(), "60 requests in a second exceeds 5 Hz");

        iface.seedSentPathRequests(3, 600);
        assertFalse(iface.shouldEgressLimitPr(), "a slow trickle is under the budget");
    }

    @Test
    @DisplayName("Egress limiting needs a minimum number of samples")
    void egressNeedsMinimumSamples() {
        iface.setEgressControl(true);
        iface.getOpFreqDeque().clear();

        assertFalse(iface.shouldEgressLimitPr(), "no samples means no judgement");
    }

    @Test
    @DisplayName("Path requests are recorded in their own windows")
    void pathRequestAccounting() {
        assertEquals(0, iface.getIpFreqDeque().size());
        assertEquals(0, iface.getOpFreqDeque().size());

        iface.receivedPathRequest();
        iface.receivedPathRequest();
        iface.sentPathRequest();

        assertEquals(2, iface.getIpFreqDeque().size());
        assertEquals(1, iface.getOpFreqDeque().size());
        assertEquals(0, iface.getIaFreqDeque().size(), "announce windows are separate");
    }

    @Test
    @DisplayName("Path request windows are capped")
    void pathRequestWindowsCapped() {
        for (int i = 0; i < 100; i++) {
            iface.receivedPathRequest();
            iface.sentPathRequest();
        }

        assertEquals(48, iface.getIpFreqDeque().size());
        assertEquals(48, iface.getOpFreqDeque().size());
    }

    // -- age() and the new-interface threshold -------------------------------

    @Test
    @DisplayName("age() grows from zero rather than running backwards")
    void ageIsPositiveAndGrowing() throws Exception {
        // Duration.between(now, created) is created - now: negative, and growing
        // more negative. Every caller tests age() < icNewTime, so an interface
        // stayed "new" forever and used the stricter thresholds permanently.
        var first = iface.age();
        assertTrue(first >= 0, "age must not be negative, was " + first);

        Thread.sleep(1100);
        assertTrue(iface.age() >= first, "age must grow, went from " + first + " to " + iface.age());
    }

    @Test
    @DisplayName("an aged interface uses the relaxed threshold, a new one the strict threshold")
    void newTimeSelectsTheThreshold() {
        iface.setIcBurstFreqNew(3.0);
        iface.setIcBurstFreq(10.0);

        // 5 Hz: above the new-interface threshold, below the relaxed one
        iface.seedAnnounces(50, 10);

        iface.setIcNewTime(3600);          // interface counts as new
        assertTrue(iface.shouldIngressLimit(), "5 Hz must exceed the new-interface threshold of 3");

        var relaxed = new TestInterface();
        relaxed.setIngressControl(true);
        relaxed.setIcBurstFreqNew(3.0);
        relaxed.setIcBurstFreq(10.0);
        relaxed.seedAnnounces(50, 10);
        relaxed.setIcNewTime(0);           // interface counts as established
        assertFalse(relaxed.shouldIngressLimit(), "5 Hz must sit under the relaxed threshold of 10");
    }

    // -- held announces ------------------------------------------------------

    @Test
    @DisplayName("held announces are capped at ic_max_held_announces")
    void heldAnnouncesAreCapped() {
        iface.setIcMaxHeldAnnounces(3);
        for (var i = 0; i < 10; i++) {
            iface.holdAnnounce(announceFor((byte) i));
        }

        assertEquals(3, iface.getHeldAnnounces().size());
    }

    @Test
    @DisplayName("a repeat announce for a held destination replaces it rather than filling the cap")
    void repeatAnnounceReplacesHeldEntry() {
        iface.setIcMaxHeldAnnounces(2);
        iface.holdAnnounce(announceFor((byte) 1));
        iface.holdAnnounce(announceFor((byte) 1));
        iface.holdAnnounce(announceFor((byte) 2));
        iface.holdAnnounce(announceFor((byte) 3));

        assertEquals(2, iface.getHeldAnnounces().size());
    }

    private static io.reticulum.packet.Packet announceFor(byte marker) {
        var packet = new io.reticulum.packet.Packet(new byte[0]);
        var hash = new byte[16];
        java.util.Arrays.fill(hash, marker);
        packet.setDestinationHash(hash);

        return packet;
    }

    // -- spawned interfaces --------------------------------------------------

    @Test
    @DisplayName("a spawned interface inherits every traffic-control setting from its parent")
    void spawnedInterfaceInheritsTrafficControl() {
        var parent = new TestInterface();
        parent.setIngressControl(false);
        parent.setIcMaxHeldAnnounces(7);
        parent.setIcBurstHold(11.0);
        parent.setIcBurstFreq(13.0);
        parent.setIcBurstFreqNew(17.0);
        parent.setIcNewTime(19);
        parent.setIcBurstPenalty(23);
        parent.setIcHeldReleaseInterval(29);
        parent.setEgressControl(true);
        parent.setEcPrFreq(31.0);
        parent.setIcPrBurstFreqNew(37.0);
        parent.setIcPrBurstFreq(41.0);
        parent.setGravity(43);

        // A server interface never receives anything itself, so anything not
        // carried across here is configuration that silently does nothing.
        var spawned = new TestInterface();
        spawned.inheritTrafficControl(parent);

        assertEquals(false, spawned.getIngressControl());
        assertEquals(7, spawned.getIcMaxHeldAnnounces());
        assertEquals(11.0, spawned.getIcBurstHold());
        assertEquals(13.0, spawned.getIcBurstFreq());
        assertEquals(17.0, spawned.getIcBurstFreqNew());
        assertEquals(19, spawned.getIcNewTime());
        assertEquals(23, spawned.getIcBurstPenalty());
        assertEquals(29, spawned.getIcHeldReleaseInterval());
        assertEquals(true, spawned.getEgressControl());
        assertEquals(31.0, spawned.getEcPrFreq());
        assertEquals(37.0, spawned.getIcPrBurstFreqNew());
        assertEquals(41.0, spawned.getIcPrBurstFreq());
        assertEquals(43, spawned.getGravity());
    }
}
