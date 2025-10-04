package io.reticulum.constant;

import static io.reticulum.constant.IdentityConstant.AES128_BLOCKSIZE;
import static io.reticulum.constant.IdentityConstant.FERNET_OVERHEAD;
import static io.reticulum.constant.ReticulumConstant.DEFAULT_PER_HOP_TIMEOUT;
import static io.reticulum.constant.ReticulumConstant.HEADER_MINSIZE;
import static io.reticulum.constant.ReticulumConstant.IFAC_MIN_SIZE;
import static io.reticulum.constant.ReticulumConstant.MTU;

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

    public static final int ECPUBSIZE = 32 + 32;
    public static final int KEYSIZE = 32;

    public static final int MDU = (int) (Math.floor((MTU - IFAC_MIN_SIZE - HEADER_MINSIZE - FERNET_OVERHEAD) / (double) AES128_BLOCKSIZE) * AES128_BLOCKSIZE - 1);

    /**
     * Timeout for link establishment in seconds per hop to destination.
     */
    public static final int ESTABLISHMENT_TIMEOUT_PER_HOP = DEFAULT_PER_HOP_TIMEOUT;

    public static final int LINK_MTU_SIZE = 3;
    //public static final double KEEPALIVE_MAX_RTT = 1.75;
    public static final int TRAFFIC_TIMEOUT_FACTOR = 6;

    /**
     * RTT timeout factor used in link timeout calculation.
     */
    public static final int KEEPALIVE_TIMEOUT_FACTOR = 4;

    /**
     * Grace period in seconds used in link timeout calculation.
     */
    public static final int STALE_GRACE = 2;
}
