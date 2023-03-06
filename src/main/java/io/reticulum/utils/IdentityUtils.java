package io.reticulum.utils;

import lombok.NoArgsConstructor;

import java.util.Arrays;

import static io.reticulum.utils.ReticulumConstant.TRUNCATED_HASHLENGTH;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.commons.codec.digest.DigestUtils.getSha256Digest;

@NoArgsConstructor(access = PRIVATE)
public class IdentityUtils {

    public static byte[] fullHash(final byte[] data) {
        return getSha256Digest().digest(data);
    }

    public static byte[] truncatedHash(byte[] data) {
        return Arrays.copyOfRange(fullHash(data), 0, TRUNCATED_HASHLENGTH / 8);
    }

    public static void loadKnownDestinations() {

    }
}
