package io.reticulum.utils;

public class LinkConstant {

    /**
     * Interval for sending keep-alive packets on established links in seconds.
     */
    public static final int KEEPALIVE = 360;

    /**
     * If no traffic or keep-alive packets are received within this period, the
     * link will be marked as stale, and a final keep-alive packet will be sent.
     * If after this no traffic or keep-alive packets are received within ``RTT`` *
     * ``KEEPALIVE_TIMEOUT_FACTOR`` + ``STALE_GRACE``, the link is considered timed out,
     * and will be torn down.
     */
    public static final int STALE_TIME = 2 * KEEPALIVE;
}
