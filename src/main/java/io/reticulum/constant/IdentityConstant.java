package io.reticulum.constant;

public class IdentityConstant {

    /**
     * The curve used for Elliptic Curve DH key exchanges
     */
    public static final String CURVE = "Curve25519";

    /**
     * X25519 key size in bits. A complete key is the concatenation of a 256 bit encryption key, and a 256 bit signing key.
     */
    public static final int KEYSIZE = 256 * 2;

    public static final int AES128_BLOCKSIZE = 16;      // In bytes
    public static final int AES256_BLOCKSIZE = 16;      // In bytes
    public static final int HASHLENGTH = 256;           // In bits
    public static final int SIGLENGTH = KEYSIZE;        // In bits
    public static final int RATCHETSIZE = 256;          // In bits

    /** Expiry time for received ratchet keys in seconds (30 days). */
    public static final long RATCHET_EXPIRY = 60L * 60 * 24 * 30;

    public static final int NAME_HASH_LENGTH = 80;

    /**
     * Length in bytes of the key derived by HKDF for token encryption.
     * <p>
     * 64 bytes selects AES-256-CBC in {@link io.reticulum.cryptography.Token}
     * (32 byte signing key + 32 byte encryption key).
     */
    public static final int DERIVED_KEY_LENGTH = 512 / 8;

    /**
     * The pre-AES-256 derived key length. Retained for parity with the reference
     * implementation, which also no longer uses it. Reticulum peers negotiate
     * AES-256-CBC unconditionally, so nothing derives a key of this length.
     */
    public static final int DERIVED_KEY_LENGTH_LEGACY = 256 / 8;

    /**
     * Combined size of the IV and HMAC fields carried by every token — see
     * {@link io.reticulum.cryptography.Token}.
     */
    public static final int TOKEN_OVERHEAD = io.reticulum.cryptography.Token.TOKEN_OVERHEAD;
}
