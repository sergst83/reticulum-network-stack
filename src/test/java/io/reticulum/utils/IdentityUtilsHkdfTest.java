package io.reticulum.utils;

import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static io.reticulum.utils.IdentityUtils.fullHash;
import static io.reticulum.utils.IdentityUtils.hkdf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The IFAC mask is as long as the packet. On a link with a large MTU that is
 * more than the 8160 bytes BouncyCastle's HKDF will produce; it threw, the
 * backbone handler dropped the connection, and a push over rngit never arrived.
 */
class IdentityUtilsHkdfTest {

    private static final byte[] IKM = new byte[64];
    private static final byte[] SALT = new byte[32];

    static {
        for (int i = 0; i < IKM.length; i++) IKM[i] = (byte) i;
        Arrays.fill(SALT, (byte) 7);
    }

    @Test
    void matchesRfc5869WithinItsLimit() {
        var bc = new HKDFBytesGenerator(new SHA256Digest());
        bc.init(new HKDFParameters(IKM, SALT, new byte[0]));
        var expected = new byte[255 * 32];
        bc.generateBytes(expected, 0, expected.length);

        assertArrayEquals(expected, hkdf(expected.length, IKM, SALT, null));
        assertArrayEquals(Arrays.copyOf(expected, 45), hkdf(45, IKM, SALT, null));
    }

    @Test
    void matchesReferenceBeyondIt() {
        // RNS.Cryptography.hkdf(length=300000, derive_from=bytes(range(64)), salt=bytes([7]*32))
        var out = hkdf(300000, IKM, SALT, null);

        assertEquals("d10588c439ba5268a91a59643650b44f", Hex.encodeHexString(Arrays.copyOfRange(out, 8160, 8176)));
        assertEquals("f454bd6c8c6f6a472637e220954341b2", Hex.encodeHexString(Arrays.copyOfRange(out, out.length - 16, out.length)));
        assertEquals("d37454993c0a16b149ab1ea7fef61224f0b517c5f95ef5b96e9c62e46dd0c382", Hex.encodeHexString(fullHash(out)));
    }
}
