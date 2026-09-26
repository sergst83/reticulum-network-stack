package io.reticulum.cryptography;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;

/**
 * This class provides a slightly modified implementation of the Fernet spec
 * found at: <a href="https://github.com/fernet/spec/blob/master/Spec.md">fernet/spec</a>.
 * <p>
 * According to the spec, a Fernet token includes a one byte VERSION and
 * eight byte TIMESTAMP field at the start of each token. These fields are
 * not relevant to Reticulum. They are therefore stripped from this
 * implementation, since they incur overhead and leak initiator metadata.
 * <p>
 * Port of {@code RNS/Cryptography/Token.py}. The wire layout is
 * {@code iv || ciphertext || HMAC-SHA256(signing_key, iv || ciphertext)}, where
 * the IV is 16 bytes and the HMAC 32 bytes — hence the fixed 48 byte
 * {@link #TOKEN_OVERHEAD} on top of the PKCS7-padded plaintext.
 * <p>
 * The cipher is selected by key length, exactly as in the reference
 * implementation: a 32 byte key selects AES-128-CBC and a 64 byte key selects
 * AES-256-CBC. Reticulum derives 64 byte keys
 * ({@code Identity.DERIVED_KEY_LENGTH}), so AES-256-CBC is what is used in
 * practice; the 32 byte form is retained for parity with the reference.
 */
public class Token {

    /**
     * Combined size of the IV and HMAC fields carried by every token.
     */
    public static final int TOKEN_OVERHEAD = 48;

    public static final int IV_SIZE = 16;
    public static final int HMAC_SIZE = 32;

    /** Key length selecting {@link Mode#AES_128_CBC}: 16 byte signing + 16 byte encryption key. */
    public static final int AES128_KEY_SIZE = 32;
    /** Key length selecting {@link Mode#AES_256_CBC}: 32 byte signing + 32 byte encryption key. */
    public static final int AES256_KEY_SIZE = 64;

    private static final String CIPHER_TRANSFORMATION = "AES/CBC/PKCS7Padding";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Cipher modes supported by the token construction. Mirrors the
     * {@code AES_128_CBC} / {@code AES_256_CBC} classes in
     * {@code RNS/Cryptography/AES.py}.
     */
    public enum Mode {
        AES_128_CBC(AES128_KEY_SIZE),
        AES_256_CBC(AES256_KEY_SIZE);

        private final int keySize;

        Mode(int keySize) {
            this.keySize = keySize;
        }

        /** Combined signing + encryption key length in bytes. */
        public int getKeySize() {
            return keySize;
        }
    }

    private final Mode mode;
    private final byte[] signingKey;
    private final byte[] encryptionKey;

    /**
     * Creates a token instance from a combined signing + encryption key.
     *
     * @param key 32 bytes for AES-128-CBC or 64 bytes for AES-256-CBC. The first
     *            half is the HMAC signing key, the second half the AES key.
     * @throws IllegalArgumentException if the key is null or not 32 or 64 bytes
     */
    public Token(final byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("Token key cannot be null");
        }

        if (key.length == AES128_KEY_SIZE) {
            this.mode = Mode.AES_128_CBC;
        } else if (key.length == AES256_KEY_SIZE) {
            this.mode = Mode.AES_256_CBC;
        } else {
            throw new IllegalArgumentException(
                    "Token key must be 128 or 256 bits, not " + (key.length * 8));
        }

        var half = key.length / 2;
        this.signingKey = Arrays.copyOfRange(key, 0, half);
        this.encryptionKey = Arrays.copyOfRange(key, half, key.length);
    }

    /**
     * Generates a new random token key for the default mode (AES-256-CBC).
     *
     * @return 64 random bytes
     */
    public static byte[] generateKey() {
        return generateKey(Mode.AES_256_CBC);
    }

    /**
     * Generates a new random token key for the requested mode.
     *
     * @param mode cipher mode to size the key for
     * @return 32 bytes for AES-128-CBC, 64 bytes for AES-256-CBC
     */
    public static byte[] generateKey(final Mode mode) {
        var key = new byte[mode.getKeySize()];
        new SecureRandom().nextBytes(key);

        return key;
    }

    /**
     * @return the cipher mode selected by this instance's key length
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * Verifies the trailing HMAC of a token against its contents.
     *
     * @param token the complete token
     * @return true if the HMAC matches
     * @throws IllegalArgumentException if the token is too short to carry an HMAC
     */
    public boolean verifyHmac(final byte[] token) {
        if (token == null || token.length <= HMAC_SIZE) {
            throw new IllegalArgumentException(
                    "Cannot verify HMAC on token of only " + (token == null ? 0 : token.length) + " bytes");
        }

        var receivedHmac = Arrays.copyOfRange(token, token.length - HMAC_SIZE, token.length);
        var expectedHmac = hmac(Arrays.copyOfRange(token, 0, token.length - HMAC_SIZE));

        return MessageDigest.isEqual(receivedHmac, expectedHmac);
    }

    /**
     * Encrypts plaintext into a token.
     *
     * @param data plaintext to encrypt
     * @return {@code iv || ciphertext || hmac}
     */
    public byte[] encrypt(final byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Token plaintext input cannot be null");
        }

        var iv = new byte[IV_SIZE];
        new SecureRandom().nextBytes(iv);

        var ciphertext = crypt(Cipher.ENCRYPT_MODE, iv, data);

        // signed_parts = iv+ciphertext; token = signed_parts + HMAC(signed_parts)
        var token = new byte[IV_SIZE + ciphertext.length + HMAC_SIZE];
        System.arraycopy(iv, 0, token, 0, IV_SIZE);
        System.arraycopy(ciphertext, 0, token, IV_SIZE, ciphertext.length);

        var signedParts = Arrays.copyOfRange(token, 0, IV_SIZE + ciphertext.length);
        System.arraycopy(hmac(signedParts), 0, token, IV_SIZE + ciphertext.length, HMAC_SIZE);

        return token;
    }

    /**
     * Verifies and decrypts a token.
     * <p>
     * The HMAC is always verified before any decryption is attempted, which is
     * what makes trial decryption against a set of candidate keys (ratchets)
     * reliable — a wrong key is rejected here rather than being caught by a
     * chance PKCS7 padding failure.
     *
     * @param token the complete token
     * @return the decrypted plaintext
     * @throws TokenValidationException if the HMAC is invalid or decryption fails
     */
    public byte[] decrypt(final byte[] token) {
        if (token == null) {
            throw new IllegalArgumentException("Token cannot be null");
        }
        if (token.length <= IV_SIZE + HMAC_SIZE) {
            throw new TokenValidationException("Token of " + token.length + " bytes is too short to decrypt");
        }
        if (!verifyHmac(token)) {
            throw new TokenValidationException("Token HMAC was invalid");
        }

        var iv = Arrays.copyOfRange(token, 0, IV_SIZE);
        var ciphertext = Arrays.copyOfRange(token, IV_SIZE, token.length - HMAC_SIZE);

        try {
            return crypt(Cipher.DECRYPT_MODE, iv, ciphertext);
        } catch (Exception e) {
            throw new TokenValidationException("Could not decrypt token: " + e.getMessage(), e);
        }
    }

    private byte[] crypt(final int cipherMode, final byte[] iv, final byte[] input) {
        try {
            var cipher = Cipher.getInstance(CIPHER_TRANSFORMATION, BouncyCastleProvider.PROVIDER_NAME);
            cipher.init(cipherMode, new SecretKeySpec(encryptionKey, "AES"), new IvParameterSpec(iv));

            return cipher.doFinal(input);
        } catch (TokenValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Token cipher operation failed: " + e.getMessage(), e);
        }
    }

    private byte[] hmac(final byte[] data) {
        var mac = new HMac(new SHA256Digest());
        mac.init(new KeyParameter(signingKey));
        mac.update(data, 0, data.length);

        var out = new byte[mac.getMacSize()];
        mac.doFinal(out, 0);

        return out;
    }
}
