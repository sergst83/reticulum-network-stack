package io.reticulum.constant;

//import static io.reticulum.constant.ResourceConstant.WINDOW_MAX_FAST;

public class ChannelConstant {
    /**
     * The initial window size at channel setup
     */
    public static final int WINDOW = 2;

    /**
     * Absolute minimum window size
     */
    public static final int WINDOW_MIN = 2;
    public static final int WINDOW_MIN_LIMIT_MEDIUM = 5;
    public static final int WINDOW_MIN_LIMIT_FAST = 16;

    /**
     * The maximum window size for transfers on slow links
     */
    public static final int WINDOW_MAX_SLOW = 5;

    /**
     * The maximum window siye for transfers on mid-speed links
     */
    public static final int WINDOW_MAX_MEDIUM = 12;

    /**
     * The maximum window size for transfers on fast links
     */
    public static final int WINDOW_MAX_FAST = 48;

    /**
     * For calculating maps and guard segments, this
     * must be set to the global maxium window.
     */
    public static final int WINDOW_MAX = WINDOW_MAX_FAST;

    /**
     * If the fast rate is sustained for this may request
     * rounds, the fast link window size will be allowed.
     */
    public static final int FAST_RATE_THRESHOLD = 10;

    /**
     * If the RTT rate is higher than this value,
     * the max window size for fast links will be used.
     */
    public static final double RTT_FAST   = 0.18;
    public static final double RTT_MEDIUM = 0.75;
    public static final double RTT_SLOW   = 1.45;

    /**
     * The minimum allowed flexibility of the window size,
     * the difference between window_max and window_min
     * will never be smaller than this value.
     */
    public static final int WINDOW_FLEXIBILITY = 4;

    public static final int SEQ_MAX    = 0xFFF;
    public static final int SEQ_MODULUS = SEQ_MAX + 1;
}

