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
    public static final int HASHLENGTH = 256;           // In bits
    public static final int SIGLENGTH = KEYSIZE;        // In bits
    public static final int RATCHETSIZE = 256;          // In bits

    public static final int NAME_HASH_LENGTH = 80;

    /**
     * This class provides a slightly modified implementation of the Fernet spec
     * found at: <a href="https://github.com/fernet/spec/blob/master/Spec.md">https://github.com/fernet/spec/blob/master/Spec.md</a>
     * <p>
     * According to the spec, a Fernet token includes a one byte VERSION and
     * eight byte TIMESTAMP field at the start of each token. These fields are
     * not relevant to Reticulum. They are therefore stripped from this
     * implementation, since they incur overhead and leak initiator metadata.
     */
    public static final int FERNET_OVERHEAD = 48; //bytes
}
