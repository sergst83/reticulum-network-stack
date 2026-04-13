package io.reticulum.interfaces.discovery;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.digests.SHA256Digest;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Proof-of-work stamp generator / validator compatible with the Python
 * {@code LXStamper} from the LXMF package.
 *
 * <h2>Algorithm overview</h2>
 * <ol>
 *   <li>Compute the <em>workblock</em> by iterating SHA-256 over the infohash
 *       {@code EXPAND_ROUNDS} (20) times.  This makes stamp generation more
 *       memory-hard than a plain hash chain.</li>
 *   <li>To <em>generate</em> a stamp, search for a random 8-byte nonce
 *       {@code s} such that the leading bit-count of
 *       {@code SHA256(workblock ‖ s)} is ≥ {@code required_value}.</li>
 *   <li>To <em>validate</em> a stamp, compute
 *       {@code SHA256(workblock ‖ stamp)}, count leading zero bits, and
 *       check against {@code required_value}.</li>
 * </ol>
 *
 * <p><b>Compatibility note:</b> The LXMF {@code LXStamper} algorithm is not
 * formally documented.  The above description is derived from the observable
 * behaviour of the Python code.  If the Python LXMF package is ever updated
 * this implementation may need adjustment.  In particular {@link #STAMP_SIZE}
 * must match {@code LXStamper.STAMP_SIZE} exactly for interoperability.
 */
@Slf4j
public final class LXStamper {

    /**
     * Size of a proof-of-work stamp in bytes.
     * Must match {@code LXStamper.STAMP_SIZE} in Python's LXMF package.
     */
    public static final int STAMP_SIZE = 8;

    /**
     * Number of SHA-256 iterations used to derive the workblock from the
     * infohash.  Matches {@code InterfaceAnnouncer.WORKBLOCK_EXPAND_ROUNDS = 20}
     * in the Python reference.
     */
    public static final int EXPAND_ROUNDS = 20;

    private static final SecureRandom RNG = new SecureRandom();

    private LXStamper() {}

    // ── Workblock ─────────────────────────────────────────────────────────────

    /**
     * Expands an infohash into a workblock by iterating SHA-256
     * {@link #EXPAND_ROUNDS} times.
     *
     * @param infohash the 32-byte SHA-256 hash of the packed info payload
     * @return the derived 32-byte workblock
     */
    public static byte[] stampWorkblock(byte[] infohash) {
        return stampWorkblock(infohash, EXPAND_ROUNDS);
    }

    /**
     * Expands an infohash into a workblock by iterating SHA-256
     * {@code expandRounds} times.
     *
     * @param infohash     the 32-byte SHA-256 hash of the packed info payload
     * @param expandRounds number of SHA-256 iterations
     * @return the derived 32-byte workblock
     */
    public static byte[] stampWorkblock(byte[] infohash, int expandRounds) {
        byte[] current = Arrays.copyOf(infohash, infohash.length);
        var digest = new SHA256Digest();
        for (int i = 0; i < expandRounds; i++) {
            digest.reset();
            digest.update(current, 0, current.length);
            byte[] next = new byte[digest.getDigestSize()];
            digest.doFinal(next, 0);
            current = next;
        }
        return current;
    }

    // ── Value measurement ─────────────────────────────────────────────────────

    /**
     * Measures the "value" of a stamp as the number of leading zero bits in
     * {@code SHA256(workblock ‖ stamp)}.
     *
     * @param workblock the 32-byte workblock derived from the infohash
     * @param stamp     the candidate stamp bytes (must be {@link #STAMP_SIZE} bytes)
     * @return number of leading zero bits
     */
    public static int stampValue(byte[] workblock, byte[] stamp) {
        var digest = new SHA256Digest();
        digest.update(workblock, 0, workblock.length);
        digest.update(stamp, 0, stamp.length);
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return countLeadingZeroBits(result);
    }

    /**
     * Returns {@code true} if the stamp's value meets {@code requiredValue}.
     *
     * @param stamp         the stamp to validate
     * @param requiredValue minimum number of leading zero bits required
     * @param workblock     the workblock to validate against
     * @return {@code true} if valid
     */
    public static boolean stampValid(byte[] stamp, int requiredValue, byte[] workblock) {
        return stampValue(workblock, stamp) >= requiredValue;
    }

    // ── Generation ────────────────────────────────────────────────────────────

    /**
     * Generates a proof-of-work stamp for {@code infohash} with a difficulty of
     * {@code stampCost} leading zero bits.
     *
     * <p>This is a CPU-bound operation whose expected running time grows
     * exponentially with {@code stampCost}.  At the default value of 14 it
     * typically completes in well under a second.
     *
     * @param infohash  the 32-byte SHA-256 hash of the packed info payload
     * @param stampCost required number of leading zero bits
     * @return a {@link #STAMP_SIZE}-byte nonce satisfying the difficulty, or
     *         {@code null} if stamp generation failed unexpectedly
     */
    public static byte[] generateStamp(byte[] infohash, int stampCost) {
        return generateStamp(infohash, stampCost, EXPAND_ROUNDS);
    }

    /**
     * Generates a proof-of-work stamp.
     *
     * @param infohash     the 32-byte SHA-256 hash of the packed info payload
     * @param stampCost    required number of leading zero bits
     * @param expandRounds number of workblock expansion rounds
     * @return a satisfying stamp, or {@code null} on failure
     */
    public static byte[] generateStamp(byte[] infohash, int stampCost, int expandRounds) {
        byte[] workblock = stampWorkblock(infohash, expandRounds);
        byte[] stamp     = new byte[STAMP_SIZE];

        while (true) {
            RNG.nextBytes(stamp);
            if (stampValue(workblock, stamp) >= stampCost) {
                return Arrays.copyOf(stamp, stamp.length);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int countLeadingZeroBits(byte[] data) {
        int count = 0;
        for (byte b : data) {
            if (b == 0) {
                count += 8;
            } else {
                int mask = 0x80;
                while ((b & mask) == 0) {
                    count++;
                    mask >>= 1;
                }
                break;
            }
        }
        return count;
    }
}