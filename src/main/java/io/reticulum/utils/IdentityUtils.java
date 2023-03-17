package io.reticulum.utils;

import io.reticulum.Transport;
import lombok.NoArgsConstructor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;

import static io.reticulum.IdentityKnownDestination.saveKnownDestinations;
import static io.reticulum.constant.ReticulumConstant.TRUNCATED_HASHLENGTH;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.commons.codec.digest.DigestUtils.getSha256Digest;

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
        return Arrays.copyOfRange(fullHash(data), 0, TRUNCATED_HASHLENGTH / 8);
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

    public static byte[] concatArrays(byte[]... arrays) throws IOException {
        try (var os = new ByteArrayOutputStream()) {
            for (byte[] array : arrays) {
                os.write(array);
            }

            return os.toByteArray();
        }
    }
}
