package io.reticulum.constant;

import static io.reticulum.constant.IdentityConstant.AES128_BLOCKSIZE;
import static io.reticulum.constant.IdentityConstant.TOKEN_OVERHEAD;
import static io.reticulum.constant.ReticulumConstant.DEFAULT_PER_HOP_TIMEOUT;
import static io.reticulum.constant.ReticulumConstant.HEADER_MINSIZE;
import static io.reticulum.constant.ReticulumConstant.IFAC_MIN_SIZE;
import static io.reticulum.constant.ReticulumConstant.MTU;

import java.util.Map;
import java.util.Set;

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
    public static final int STALE_FACTOR = 2;
    public static final int STALE_TIME = STALE_FACTOR * KEEPALIVE;

    public static final int ECPUBSIZE = 32 + 32;
    public static final int KEYSIZE = 32;

    public static final int MDU = (int) (Math.floor((MTU - IFAC_MIN_SIZE - HEADER_MINSIZE - TOKEN_OVERHEAD) / (double) AES128_BLOCKSIZE) * AES128_BLOCKSIZE - 1);

    /**
     * Timeout for link establishment in seconds per hop to destination.
     */
    public static final int ESTABLISHMENT_TIMEOUT_PER_HOP = DEFAULT_PER_HOP_TIMEOUT;

    public static final int LINK_MTU_SIZE = 3;

    /** Bitmask for the MTU field within signalling bytes (21 bits). */
    public static final int MTU_BYTEMASK = 0x1FFFFF;
    /** Bitmask for the mode field within the first signalling byte. */
    public static final int MODE_BYTEMASK = 0xE0;
    /** AES-128-CBC link cipher mode identifier. */
    public static final int MODE_AES128_CBC = 0x00;
    /** AES-256-CBC link cipher mode identifier (default). */
    public static final int MODE_AES256_CBC = 0x01;
    /** Reserved: AES-256-GCM. Not implemented. */
    public static final int MODE_AES256_GCM = 0x02;
    /** Reserved for a future one-time-pad mode. Not implemented. */
    public static final int MODE_OTP_RESERVED = 0x03;
    /** Reserved for a future post-quantum mode. Not implemented. */
    public static final int MODE_PQ_RESERVED_1 = 0x04;
    /** Reserved for a future post-quantum mode. Not implemented. */
    public static final int MODE_PQ_RESERVED_2 = 0x05;
    /** Reserved for a future post-quantum mode. Not implemented. */
    public static final int MODE_PQ_RESERVED_3 = 0x06;
    /** Reserved for a future post-quantum mode. Not implemented. */
    public static final int MODE_PQ_RESERVED_4 = 0x07;

    /**
     * Link cipher modes this implementation will establish links with. Modes
     * outside this set are rejected during handshake and when generating
     * signalling bytes, mirroring {@code Link.ENABLED_MODES} in the reference
     * implementation.
     */
    public static final Set<Integer> ENABLED_MODES = Set.of(MODE_AES256_CBC);

    /** Human-readable names for the mode identifiers, for logging. */
    public static final Map<Integer, String> MODE_DESCRIPTIONS = Map.of(
            MODE_AES128_CBC, "AES_128_CBC",
            MODE_AES256_CBC, "AES_256_CBC",
            MODE_AES256_GCM, "AES_256_GCM",
            MODE_OTP_RESERVED, "OTP_RESERVED",
            MODE_PQ_RESERVED_1, "PQ_RESERVED_1",
            MODE_PQ_RESERVED_2, "PQ_RESERVED_2",
            MODE_PQ_RESERVED_3, "PQ_RESERVED_3",
            MODE_PQ_RESERVED_4, "PQ_RESERVED_4"
    );

    /** Default link cipher mode. */
    public static final int MODE_DEFAULT = MODE_AES256_CBC;

    /**
     * Returns the derived key length in bytes required by a link cipher mode.
     *
     * @param mode one of the MODE_* constants
     * @return 32 for AES-128-CBC, 64 for AES-256-CBC
     * @throws IllegalArgumentException for any other mode
     */
    public static int derivedKeyLength(final int mode) {
        switch (mode) {
            case MODE_AES128_CBC:
                return 32;
            case MODE_AES256_CBC:
                return 64;
            default:
                throw new IllegalArgumentException("Invalid link mode " + modeDescription(mode));
        }
    }

    /**
     * @return the human-readable name of a mode, or its hex value if unknown
     */
    public static String modeDescription(final int mode) {
        return MODE_DESCRIPTIONS.getOrDefault(mode, String.format("0x%02x", mode));
    }

    //public static final double KEEPALIVE_MAX_RTT = 1.75;
    public static final int TRAFFIC_TIMEOUT_FACTOR = 6;

    /**
     * RTT timeout factor used in link timeout calculation.
     */
    public static final int KEEPALIVE_TIMEOUT_FACTOR = 4;

    /**
     * Grace period in seconds used in link timeout calculation.
     */
    public static final int STALE_GRACE = 5;
}
