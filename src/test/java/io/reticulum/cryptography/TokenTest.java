package io.reticulum.cryptography;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity tests for {@link Token} against the reference implementation.
 * <p>
 * Every {@code *_TOKEN} constant below was produced by Python RNS 1.5.2
 * ({@code RNS/Cryptography/Token.py}) from the corresponding key and plaintext,
 * so a passing assertion means the Java port decrypts what the reference
 * implementation actually emits — not merely that it agrees with itself.
 * Tokens carry a random IV, so encryption is verified by round-trip and by
 * length rather than by a fixed expected ciphertext.
 */
class TokenTest {

    private static final String PLAINTEXT = "The quick brown fox jumps over the lazy dog";

    private static final String AES128_KEY =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    private static final String AES128_TOKEN =
            "93406475b83985801bef33c74df9c4770d9ed16bb89a18bc18e97f07a1a533a9"
            + "39769a2b7af5755e3996bd3fc638e0f6f8cefcf1283b91435f25239a193cccf3"
            + "4b9bc41e888d162b6985b0ad0e19e27038df10a17000fad5dd31344b66374a59";

    private static final String AES256_KEY =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
            + "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f";

    private static final String AES256_TOKEN =
            "b767bf176d0ceccbbd616f1b996351fca20704116d2a97149a2a7d324734a66f"
            + "301bc57f25f445e6a83866ceb96f38b268a6ff5cd5fbda52fdd314b390e6bb35"
            + "b934acbc7501fb09e898da1bb246caf45d72e59c394f3b917c21c7b7606bfd04";

    /** Empty plaintext — exercises a full 16-byte PKCS7 pad block. */
    private static final String AES256_EMPTY_TOKEN =
            "2810308b86e70e6976d86b93d9b7718d15efe06861dd78837dc822543aea1a71"
            + "ef6de4a4126ac1123845315ecb90fab83c5ffc6966574ad3dab3bb4ed8720af3";

    /** Exactly one block of plaintext — exercises the extra pad block. */
    private static final String AES256_BLOCK16_PLAINTEXT = "0123456789abcdef";

    private static final String AES256_BLOCK16_TOKEN =
            "834a591c44134798168eee729d2eab16492265675adaa514102b1b393d9120dc"
            + "e169950376cbdb9e1fe8bbfc07fff4f11150688d5b8c16ea642c80b2ac5caf52"
            + "312adf6a81027b21deaf74aa01991c27";

    private static byte[] hex(String s) throws DecoderException {
        return Hex.decodeHex(s);
    }

    @Test
    @DisplayName("Key length selects the cipher mode, as in Token.py")
    void keyLengthSelectsMode() throws DecoderException {
        assertEquals(Token.Mode.AES_128_CBC, new Token(hex(AES128_KEY)).getMode());
        assertEquals(Token.Mode.AES_256_CBC, new Token(hex(AES256_KEY)).getMode());
    }

    @Test
    @DisplayName("Invalid key lengths are rejected")
    void invalidKeyLengthRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Token(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> new Token(new byte[48]));
        assertThrows(IllegalArgumentException.class, () -> new Token(new byte[65]));
        assertThrows(IllegalArgumentException.class, () -> new Token(null));

        var e = assertThrows(IllegalArgumentException.class, () -> new Token(new byte[16]));
        assertEquals("Token key must be 128 or 256 bits, not 128", e.getMessage());
    }

    @Test
    @DisplayName("generateKey defaults to 64 bytes (AES-256), matching Token.generate_key()")
    void generateKeyDefaultsToAes256() {
        assertEquals(64, Token.generateKey().length);
        assertEquals(64, Token.generateKey(Token.Mode.AES_256_CBC).length);
        assertEquals(32, Token.generateKey(Token.Mode.AES_128_CBC).length);
    }

    @Test
    @DisplayName("Decrypts an AES-128-CBC token produced by Python RNS 1.5.2")
    void decryptsReferenceAes128Token() throws DecoderException {
        var token = new Token(hex(AES128_KEY));

        assertArrayEquals(PLAINTEXT.getBytes(UTF_8), token.decrypt(hex(AES128_TOKEN)));
    }

    @Test
    @DisplayName("Decrypts an AES-256-CBC token produced by Python RNS 1.5.2")
    void decryptsReferenceAes256Token() throws DecoderException {
        var token = new Token(hex(AES256_KEY));

        assertArrayEquals(PLAINTEXT.getBytes(UTF_8), token.decrypt(hex(AES256_TOKEN)));
    }

    @Test
    @DisplayName("Decrypts reference tokens at PKCS7 padding boundaries")
    void decryptsReferencePaddingEdgeCases() throws DecoderException {
        var token = new Token(hex(AES256_KEY));

        assertArrayEquals(new byte[0], token.decrypt(hex(AES256_EMPTY_TOKEN)));
        assertArrayEquals(
                AES256_BLOCK16_PLAINTEXT.getBytes(UTF_8),
                token.decrypt(hex(AES256_BLOCK16_TOKEN)));
    }

    @Test
    @DisplayName("Reference tokens carry exactly TOKEN_OVERHEAD bytes over the padded plaintext")
    void referenceTokenLayout() throws DecoderException {
        // 43 bytes of plaintext pads to 48; 48 + 48 overhead = 96, for both modes
        assertEquals(48, Token.TOKEN_OVERHEAD);
        assertEquals(48 + Token.TOKEN_OVERHEAD, hex(AES128_TOKEN).length);
        assertEquals(48 + Token.TOKEN_OVERHEAD, hex(AES256_TOKEN).length);
        // Empty plaintext pads to a single 16-byte block
        assertEquals(16 + Token.TOKEN_OVERHEAD, hex(AES256_EMPTY_TOKEN).length);
        // 16 bytes pads to 32
        assertEquals(32 + Token.TOKEN_OVERHEAD, hex(AES256_BLOCK16_TOKEN).length);
    }

    @Test
    @DisplayName("Round-trips in both modes")
    void roundTrip() throws DecoderException {
        for (var key : new byte[][]{hex(AES128_KEY), hex(AES256_KEY)}) {
            var token = new Token(key);
            for (var plaintext : new byte[][]{
                    new byte[0],
                    "a".getBytes(UTF_8),
                    AES256_BLOCK16_PLAINTEXT.getBytes(UTF_8),
                    PLAINTEXT.getBytes(UTF_8),
                    new byte[1000]}) {

                var encrypted = token.encrypt(plaintext);
                assertArrayEquals(plaintext, token.decrypt(encrypted));
            }
        }
    }

    @Test
    @DisplayName("A tampered HMAC is rejected rather than decrypted")
    void tamperedHmacRejected() throws DecoderException {
        var token = new Token(hex(AES256_KEY));
        var valid = hex(AES256_TOKEN);

        var tampered = Arrays.copyOf(valid, valid.length);
        tampered[tampered.length - 1] ^= 0x01;

        assertFalse(token.verifyHmac(tampered));
        assertThrows(TokenValidationException.class, () -> token.decrypt(tampered));
        assertTrue(token.verifyHmac(valid));
    }

    @Test
    @DisplayName("Tampered ciphertext is caught by the HMAC, not by padding luck")
    void tamperedCiphertextRejected() throws DecoderException {
        var token = new Token(hex(AES256_KEY));
        var valid = hex(AES256_TOKEN);

        var tampered = Arrays.copyOf(valid, valid.length);
        tampered[Token.IV_SIZE] ^= 0x01;

        assertThrows(TokenValidationException.class, () -> token.decrypt(tampered));
    }

    @Test
    @DisplayName("Decrypting with the wrong key fails deterministically")
    void wrongKeyRejected() throws DecoderException {
        // Without HMAC verification this would succeed roughly 1 time in 256 on
        // a chance-valid PKCS7 pad, returning garbage. It must always throw.
        var wrongKey = hex(AES256_KEY);
        wrongKey[0] ^= 0x01;
        var token = new Token(wrongKey);

        assertThrows(TokenValidationException.class, () -> token.decrypt(hex(AES256_TOKEN)));
    }

    @Test
    @DisplayName("Tokens too short to hold an IV and HMAC are rejected")
    void shortTokenRejected() {
        var token = new Token(new byte[64]);

        assertThrows(TokenValidationException.class, () -> token.decrypt(new byte[48]));
        assertThrows(IllegalArgumentException.class, () -> token.verifyHmac(new byte[32]));
    }
}
