package io.reticulum.constant;

import static io.reticulum.constant.LinkConstant.MDU;

public class ResourceConstant {

    public static final int RESPONSE_MAX_GRACE_TIME = 10;
    public static final byte HASHMAP_IS_EXHAUSTED = (byte) 0xFF;
    /**
     * Number of bytes in a map hash
     */
    public static final int MAPHASH_LEN = 4;
    /**
     * The initial window size at beginning of transfer
     */
    public static final int WINDOW = 4;
    /**
     * Absolute minimum window size during transfer
     */
    public static final int WINDOW_MIN = 1;
    /**
     * The maximum window size for transfers on slow links
     */
    public static final int WINDOW_MAX_SLOW = 10;
    /**
     * The maximum window size for transfers on fast links
     */
    public static final int WINDOW_MAX_FAST = 75;
    /**
     * For calculating maps and guard segments, this must be set to the global maximum window.
     */
    public static final int WINDOW_MAX = WINDOW_MAX_FAST;
    /**
     * If the fast rate is sustained for this many request rounds, the fast link window size will be allowed.
     */
    public static final int FAST_RATE_THRESHOLD = WINDOW_MAX_SLOW - WINDOW - 2;
    /**
     * If the RTT rate is higher than this value,
     * the max window size for fast links will be used.
     * The default is 50 Kbps (the value is stored in
     * bytes per second, hence the "/ 8").
     */
    public static final int RATE_FAST = (50 * 1000) / 8;
    /**
     * The minimum allowed flexibility of the window size.
     * The difference between window_max and window_min
     * will never be smaller than this value.
     */
    public static final int WINDOW_FLEXIBILITY = 4;
    public static final int SDU = ReticulumConstant.MDU;
    public static final int RANDOM_HASH_SIZE = 4;
    /**
     * This is an indication of what the
     * maximum size a resource should be, if
     * it is to be handled within reasonable
     * time constraint, even on small systems.
     * <p>
     * A small system in this regard is
     * defined as a Raspberry Pi, which should
     * be able to compress, encrypt and hash-map
     * the resource in about 10 seconds.
     * <p>
     * This constant will be used when determining
     * how to sequence the sending of large resources.
     * <p>
     * Capped at 16777215 (0xFFFFFF) per segment to
     * fit in 3 bytes in resource advertisements.
     */
    public static final int MAX_EFFICIENT_SIZE = 16 * 1024 * 1024 - 1;
    public static final int AUTO_COMPRESS_MAX_SIZE = MAX_EFFICIENT_SIZE;
    public static final int PART_TIMEOUT_FACTOR = 4;
    public static final int PART_TIMEOUT_FACTOR_AFTER_RTT = 2;
    public static final int MAX_RETRIES = 8;
    public static final int MAX_ADV_RETRIES = 4;
    public static final long SENDER_GRACE_TIME = 10_000;
    public static final long RETRY_GRACE_TIME = 250;
    public static final long PER_RETRY_DELAY = 500;

    public static final int WATCHDOG_MAX_SLEEP = 1_000;

    public static final int HASHMAP_IS_NOT_EXHAUSTED = 0x00;

    private static final int OVERHEAD = 134;
    public static final int HASHMAP_MAX_LEN = (int) Math.floor((MDU - OVERHEAD) / MAPHASH_LEN);
    static {
        assert HASHMAP_MAX_LEN > 0 : "The configured MTU is too small to include any map hashes in resource advertisments";
    }
    public static final int COLLISION_GUARD_SIZE = 2 * WINDOW_MAX + HASHMAP_MAX_LEN;
}
