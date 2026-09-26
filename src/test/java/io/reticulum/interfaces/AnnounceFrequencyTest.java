package io.reticulum.interfaces;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the announce frequency calculation to the reference algorithm in
 * {@code RNS/Interfaces/Interface.py:346-366}: {@code hz = n / span}, where
 * {@code span} is the age of the oldest retained sample.
 * <p>
 * The previous implementation summed inter-sample deltas and then added
 * {@code Duration.between(now, oldest)} — a negative term — so the result was
 * always zero or negative. Since the ingress controls gate on
 * {@code frequency < threshold}, a never-positive frequency meant held
 * announces were always released and the IC_BURST_* thresholds never bit.
 */
class AnnounceFrequencyTest {

    /** Minimal concrete interface; the frequency logic lives in the abstract base. */
    private static final class TestInterface extends AbstractConnectionInterface {
        @Override public void processIncoming(byte[] data) { }
        @Override public void processOutgoing(byte[] data) { }
        @Override public void launch() { }
        @Override public void run() { }
    }

    private TestInterface iface;

    @BeforeEach
    void setUp() {
        iface = new TestInterface();
    }

    @Test
    @DisplayName("Incoming frequency is zero until more than IC_DEQUE_MIN_SAMPLE samples exist")
    void incomingRequiresMinimumSamples() {
        assertEquals(0, iface.incomingAnnounceFrequency(), "no samples");

        iface.recordReceivedAnnounce();
        assertEquals(0, iface.incomingAnnounceFrequency(), "1 sample");

        iface.recordReceivedAnnounce();
        assertEquals(0, iface.incomingAnnounceFrequency(), "2 samples, still not > MIN_SAMPLE");

        iface.recordReceivedAnnounce();
        assertTrue(iface.incomingAnnounceFrequency() > 0, "3 samples must yield a positive rate");
    }

    @Test
    @DisplayName("Outgoing frequency is zero until more than one sample exists")
    void outgoingRequiresTwoSamples() {
        assertEquals(0, iface.outgoingAnnounceFrequency(), "no samples");

        iface.recordSentAnnounce();
        assertEquals(0, iface.outgoingAnnounceFrequency(), "1 sample");

        iface.recordSentAnnounce();
        assertTrue(iface.outgoingAnnounceFrequency() > 0, "2 samples must yield a positive rate");
    }

    /**
     * The regression that mattered: the rate must never be negative, because the
     * ingress controls compare it against a positive threshold.
     */
    @Test
    @DisplayName("Frequency is strictly positive once enough samples exist")
    void frequencyIsPositive() {
        for (int i = 0; i < 10; i++) {
            iface.recordReceivedAnnounce();
            iface.recordSentAnnounce();
        }

        assertTrue(iface.incomingAnnounceFrequency() > 0,
                "incoming rate was " + iface.incomingAnnounceFrequency());
        assertTrue(iface.outgoingAnnounceFrequency() > 0,
                "outgoing rate was " + iface.outgoingAnnounceFrequency());
    }

    @Test
    @DisplayName("Frequency equals sample count divided by the span to the oldest sample")
    void frequencyMatchesReferenceFormula() {
        // Oldest sample one second back, so span ~= 1 s and hz ~= n
        var oneSecondAgo = Instant.now().minusSeconds(1);
        for (int i = 0; i < 5; i++) {
            iface.getIaFreqDeque().add(oneSecondAgo);
        }

        var hz = iface.incomingAnnounceFrequency();

        assertTrue(hz > 4.0 && hz < 5.5,
                "5 samples over ~1 s should be ~5 Hz, got " + hz);
    }

    @Test
    @DisplayName("Sample windows are capped at the reference sample count")
    void windowsAreCapped() {
        for (int i = 0; i < 100; i++) {
            iface.recordReceivedAnnounce();
            iface.recordSentAnnounce();
        }

        assertEquals(AbstractConnectionInterface.IA_FREQ_SAMPLES, iface.getIaFreqDeque().size());
        assertEquals(AbstractConnectionInterface.OA_FREQ_SAMPLES, iface.getOaFreqDeque().size());
        assertEquals(48, AbstractConnectionInterface.IA_FREQ_SAMPLES);
    }

    @Test
    @DisplayName("Ingress control defaults match the RNS 1.5.2 tuned values")
    void ingressControlDefaults() {
        assertEquals(15.0, iface.getIcBurstHold(), "IC_BURST_HOLD, was 60");
        assertEquals(3.0, iface.getIcBurstFreqNew(), "IC_BURST_FREQ_NEW, was 3.5");
        assertEquals(10.0, iface.getIcBurstFreq(), "IC_BURST_FREQ, was 12");
        assertEquals(15, iface.getIcBurstPenalty(), "IC_BURST_PENALTY seconds, was 300");
        assertEquals(5, iface.getIcHeldReleaseInterval(), "IC_HELD_RELEASE_INTERVAL seconds, was 30");
        assertEquals(256, iface.getIcMaxHeldAnnounces(), "MAX_HELD_ANNOUNCES");
        assertEquals(7200, iface.getIcNewTime(), "IC_NEW_TIME");
    }
}
