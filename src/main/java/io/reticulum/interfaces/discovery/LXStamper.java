package io.reticulum.interfaces.discovery;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Proof-of-work stamp generator / validator compatible with the Python
 * {@code LXStamper} from the LXMF package.
 *
 * <h2>Algorithm overview</h2>
 * <ol>
 *   <li>Compute the <em>workblock</em> by running {@code expandRounds} HKDF
 *       derivations over the infohash, each producing 256 bytes, concatenated
 *       into a {@code expandRounds * 256}-byte workblock.  For each round
 *       {@code n}, the HKDF salt is {@code SHA-256(material ‖ msgpack_fixint(n))}.</li>
 *   <li>To <em>generate</em> a stamp, search for a random {@link #STAMP_SIZE}-byte
 *       nonce {@code s} such that the number of leading zero bits of
 *       {@code SHA-256(workblock ‖ s)} is ≥ {@code stampCost}.</li>
 *   <li>To <em>validate</em> a stamp, compute
 *       {@code SHA-256(workblock ‖ stamp)}, count leading zero bits, and check
 *       against {@code requiredValue}.</li>
 * </ol>
 *
 * <p><b>Compatibility:</b> Matches the Python LXMF {@code LXStamper} module.
 * {@link #STAMP_SIZE} equals {@code RNS.Identity.HASHLENGTH // 8 = 32}.
 * The workblock is generated using RFC 5869 HKDF (HMAC-SHA-256) in the same
 * way as Python's {@code RNS.Cryptography.hkdf()}.
 */
@Slf4j
public final class LXStamper {

    /**
     * Size of a proof-of-work stamp in bytes.
     * Matches {@code LXStamper.STAMP_SIZE = RNS.Identity.HASHLENGTH // 8 = 32}
     * in Python's LXMF package.
     */
    public static final int STAMP_SIZE = 32;

    /**
     * Number of HKDF rounds used to derive the workblock.
     * Matches {@code InterfaceAnnouncer.WORKBLOCK_EXPAND_ROUNDS = 20}
     * in the Python Discovery.py reference.
     */
    public static final int EXPAND_ROUNDS = 20;

    /** Bytes produced per HKDF round. */
    private static final int BYTES_PER_ROUND = 256;

    private static final SecureRandom RNG = new SecureRandom();

    private LXStamper() {}

    // ── Workblock ─────────────────────────────────────────────────────────────

    /**
     * Expands an infohash into a workblock using {@link #EXPAND_ROUNDS} HKDF rounds.
     *
     * <p>Each round {@code n} derives {@link #BYTES_PER_ROUND} bytes using HKDF
     * with {@code salt = SHA-256(material ‖ msgpack_fixint(n))} and
     * {@code context = b""}.  The workblock is the concatenation of all rounds.
     *
     * @param infohash the 32-byte SHA-256 hash of the packed info payload
     * @return a {@code EXPAND_ROUNDS * BYTES_PER_ROUND}-byte workblock
     */
    public static byte[] stampWorkblock(byte[] infohash) {
        return stampWorkblock(infohash, EXPAND_ROUNDS);
    }

    /**
     * Expands an infohash into a workblock using {@code expandRounds} HKDF rounds.
     *
     * @param infohash     the 32-byte SHA-256 hash of the packed info payload
     * @param expandRounds number of HKDF rounds to perform
     * @return a {@code expandRounds * BYTES_PER_ROUND}-byte workblock
     */
    public static byte[] stampWorkblock(byte[] infohash, int expandRounds) {
        byte[] workblock = new byte[expandRounds * BYTES_PER_ROUND];
        var sha256 = new SHA256Digest();

        for (int n = 0; n < expandRounds; n++) {
            // salt = SHA-256(material || msgpack_fixint(n))
            // For n in [0, 127], msgpack fixint is just the byte value of n.
            sha256.reset();
            sha256.update(infohash, 0, infohash.length);
            sha256.update((byte) n);   // msgpack positive fixint for n < 128
            byte[] salt = new byte[sha256.getDigestSize()];
            sha256.doFinal(salt, 0);

            // HKDF(length=256, ikm=material, salt=salt, info=b"")
            var hkdf = new HKDFBytesGenerator(new SHA256Digest());
            hkdf.init(new HKDFParameters(infohash, salt, new byte[0]));
            hkdf.generateBytes(workblock, n * BYTES_PER_ROUND, BYTES_PER_ROUND);
        }

        return workblock;
    }

    // ── Value measurement ─────────────────────────────────────────────────────

    /**
     * Measures the "value" of a stamp as the number of leading zero bits in
     * {@code SHA-256(workblock ‖ stamp)}.
     *
     * @param workblock the workblock derived from the infohash
     * @param stamp     the stamp bytes (must be {@link #STAMP_SIZE} bytes)
     * @return number of leading zero bits (0–256)
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
     * <p>Expected search cost is approximately {@code 2^stampCost} SHA-256
     * evaluations.  At the default value of 14 this completes in milliseconds
     * on any modern CPU.
     *
     * @param infohash  the 32-byte SHA-256 hash of the packed info payload
     * @param stampCost required number of leading zero bits
     * @return a {@link #STAMP_SIZE}-byte nonce satisfying the difficulty
     */
    public static byte[] generateStamp(byte[] infohash, int stampCost) {
        return generateStamp(infohash, stampCost, EXPAND_ROUNDS);
    }

    /**
     * Generates a proof-of-work stamp.
     *
     * @param infohash     the 32-byte SHA-256 hash of the packed info payload
     * @param stampCost    required number of leading zero bits
     * @param expandRounds number of HKDF rounds for workblock generation
     * @return a satisfying {@link #STAMP_SIZE}-byte stamp
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
