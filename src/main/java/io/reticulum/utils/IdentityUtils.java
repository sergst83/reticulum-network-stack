package io.reticulum.utils;

import io.reticulum.Transport;
import lombok.NoArgsConstructor;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;

import static io.reticulum.constant.ReticulumConstant.TRUNCATED_HASHLENGTH;
import static io.reticulum.identity.IdentityKnownDestination.saveKnownDestinations;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.commons.codec.digest.DigestUtils.getSha256Digest;
import static org.apache.commons.lang3.ArrayUtils.subarray;

@NoArgsConstructor(access = PRIVATE)
public class IdentityUtils {

    public static byte[] fullHash(final byte[] data) {
        return getSha256Digest().digest(data);
    }

    /**
     * Get a truncated SHA-256 hash of passed data.
     *
     * @param data Data to be hashed.
     * @return Truncated SHA-256 hash
     */
    public static byte[] truncatedHash(byte[] data) {
        return subarray(fullHash(data), 0, TRUNCATED_HASHLENGTH / 8);
    }

    /**
     * Get a random SHA-256 hash.
     *
     * @return Truncated SHA-256 hash of random data
     */
    public static byte[] getRandomHash() {
        return truncatedHash(SecureRandom.getSeed(TRUNCATED_HASHLENGTH / 8));
    }

    public static void persistData() {
        if (Transport.getInstance().getOwner().isConnectedToSharedInstance()) {
            saveKnownDestinations();
        }
    }

    public static void exitHandler() {
        persistData();
    }

    /**
     * HKDF-SHA256 as {@code RNS.Cryptography.hkdf()} computes it. Unlike RFC 5869
     * (and BouncyCastle's generator), the output length is not capped at
     * 255 blocks: the counter byte wraps around, so IFAC masks can cover
     * packets of any size.
     *
     * @param length     output length in bytes
     * @param deriveFrom input key material
     * @param salt       salt; null or empty means 32 zero bytes
     * @param context    info; null means none
     */
    public static byte[] hkdf(int length, byte[] deriveFrom, byte[] salt, byte[] context) {
        if (length < 1) throw new IllegalArgumentException("Invalid output key length");
        if (deriveFrom == null || deriveFrom.length == 0) {
            throw new IllegalArgumentException("Cannot derive key from empty input material");
        }
        if (salt == null || salt.length == 0) salt = new byte[32];
        if (context == null) context = new byte[0];

        var hmac = new HMac(new SHA256Digest());
        hmac.init(new KeyParameter(salt));
        hmac.update(deriveFrom, 0, deriveFrom.length);
        var prk = new byte[32];
        hmac.doFinal(prk, 0);

        hmac.init(new KeyParameter(prk));
        var derived = new byte[length];
        var block = new byte[0];
        for (int i = 0, offset = 0; offset < length; i++, offset += 32) {
            hmac.update(block, 0, block.length);
            hmac.update(context, 0, context.length);
            hmac.update((byte) (i + 1));
            block = new byte[32];
            hmac.doFinal(block, 0);
            System.arraycopy(block, 0, derived, offset, Math.min(32, length - offset));
        }
        return derived;
    }

    public static byte[] concatArrays(byte[]... arrays) {
        try (var os = new ByteArrayOutputStream()) {
            for (byte[] array : arrays) {
                os.write(array);
            }

            return os.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
