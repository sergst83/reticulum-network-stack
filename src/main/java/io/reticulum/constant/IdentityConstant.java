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

    public static final int AES128_BLOCKSIZE = 16;       // In bytes
    public static final int HASHLENGTH = 256;         // In bits
    public static final int SIGLENGTH = KEYSIZE; // In bits

    public static final int NAME_HASH_LENGTH = 80;
}
