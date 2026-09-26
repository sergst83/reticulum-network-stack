package io.reticulum.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.reticulum.constant.ResourceConstant.AUTO_COMPRESS_MAX_SIZE;
import static io.reticulum.constant.ResourceConstant.FAST_RATE_THRESHOLD;
import static io.reticulum.constant.ResourceConstant.HMU_WAIT_FACTOR;
import static io.reticulum.constant.ResourceConstant.MAPHASH_LEN;
import static io.reticulum.constant.ResourceConstant.MAX_ADV_RETRIES;
import static io.reticulum.constant.ResourceConstant.MAX_EFFICIENT_SIZE;
import static io.reticulum.constant.ResourceConstant.MAX_RETRIES;
import static io.reticulum.constant.ResourceConstant.METADATA_MAX_SIZE;
import static io.reticulum.constant.ResourceConstant.PART_TIMEOUT_FACTOR;
import static io.reticulum.constant.ResourceConstant.PART_TIMEOUT_FACTOR_AFTER_RTT;
import static io.reticulum.constant.ResourceConstant.PROOF_TIMEOUT_FACTOR;
import static io.reticulum.constant.ResourceConstant.RANDOM_HASH_SIZE;
import static io.reticulum.constant.ResourceConstant.RATE_FAST;
import static io.reticulum.constant.ResourceConstant.RATE_VERY_SLOW;
import static io.reticulum.constant.ResourceConstant.RESPONSE_MAX_GRACE_TIME;
import static io.reticulum.constant.ResourceConstant.VERY_SLOW_RATE_THRESHOLD;
import static io.reticulum.constant.ResourceConstant.WINDOW;
import static io.reticulum.constant.ResourceConstant.WINDOW_FLEXIBILITY;
import static io.reticulum.constant.ResourceConstant.WINDOW_MAX;
import static io.reticulum.constant.ResourceConstant.WINDOW_MAX_FAST;
import static io.reticulum.constant.ResourceConstant.WINDOW_MAX_SLOW;
import static io.reticulum.constant.ResourceConstant.WINDOW_MAX_VERY_SLOW;
import static io.reticulum.constant.ResourceConstant.WINDOW_MIN;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins every {@code Resource} protocol constant to the value in Python RNS
 * 1.5.2 ({@code RNS/Resource.py:55-140}).
 * <p>
 * These are not arbitrary tuning knobs. {@code MAX_EFFICIENT_SIZE} in
 * particular is a wire-visible bound: the reference peer rejects any
 * advertisement whose transfer size exceeds {@code MAX_EFFICIENT_SIZE * 3}, so
 * a local value larger than the reference's silently produces resources that
 * the other side drops.
 */
class ResourceConstantParityTest {

    @Test
    @DisplayName("Window control constants match RNS/Resource.py")
    void windowConstants() {
        assertEquals(4, WINDOW);
        assertEquals(2, WINDOW_MIN);
        assertEquals(10, WINDOW_MAX_SLOW);
        assertEquals(4, WINDOW_MAX_VERY_SLOW);
        assertEquals(75, WINDOW_MAX_FAST);
        assertEquals(WINDOW_MAX_FAST, WINDOW_MAX);
        assertEquals(4, WINDOW_FLEXIBILITY);
    }

    @Test
    @DisplayName("Rate thresholds match RNS/Resource.py")
    void rateConstants() {
        assertEquals(4, FAST_RATE_THRESHOLD, "WINDOW_MAX_SLOW - WINDOW - 2");
        assertEquals(2, VERY_SLOW_RATE_THRESHOLD);
        assertEquals(6250, RATE_FAST, "50 Kbps in bytes per second");
        assertEquals(250, RATE_VERY_SLOW, "2 Kbps in bytes per second");
    }

    @Test
    @DisplayName("Size limits match RNS/Resource.py")
    void sizeConstants() {
        assertEquals(1048575, MAX_EFFICIENT_SIZE, "1 MiB - 1");
        assertEquals(16777215, METADATA_MAX_SIZE, "16 MiB - 1, fits in 3 bytes");
        assertEquals(67108864, AUTO_COMPRESS_MAX_SIZE, "64 MiB");
        assertEquals(4, MAPHASH_LEN);
        assertEquals(4, RANDOM_HASH_SIZE);
    }

    @Test
    @DisplayName("Timeout and retry constants match RNS/Resource.py")
    void timeoutConstants() {
        assertEquals(4, PART_TIMEOUT_FACTOR);
        assertEquals(2, PART_TIMEOUT_FACTOR_AFTER_RTT);
        assertEquals(3, PROOF_TIMEOUT_FACTOR);
        assertEquals(3.5, HMU_WAIT_FACTOR);
        assertEquals(16, MAX_RETRIES);
        assertEquals(4, MAX_ADV_RETRIES);
        assertEquals(10, RESPONSE_MAX_GRACE_TIME);
    }

    /**
     * The bound the reference applies in {@code ResourceAdvertisement.unpack}
     * (RNS/Resource.py:1374). Spelled out here because it is the number that
     * decides whether a Java-originated transfer survives at a Python peer.
     */
    @Test
    @DisplayName("Maximum advertised transfer size matches the reference bound")
    void maxAdvertisedTransferSize() {
        assertEquals(3145725L, MAX_EFFICIENT_SIZE * 3L);
    }
}
