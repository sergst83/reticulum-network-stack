package io.reticulum;

import com.macasaet.fernet.Key;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ArrayUtils;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import javax.crypto.spec.IvParameterSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;

import static io.reticulum.utils.IdentityUtils.truncatedHash;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * This class is used to manage identities in Reticulum. It provides methods
 * for encryption, decryption, signatures and verification, and is the basis
 * for all encrypted communication over Reticulum networks.
 */
@Slf4j
@ToString
@RequiredArgsConstructor
@Getter
public class Identity {

    private static final int KEYSIZE = 256 * 2;

    private final boolean createKeys;

    private byte[] prvBytes;
    private X25519PrivateKeyParameters prv;
    private byte[] pubBytes;
    private X25519PublicKeyParameters pub;

    private byte[] sigPrvBytes;
    private Ed25519PrivateKeyParameters sigPrv;
    private byte[] sigPubBytes;
    private Ed25519PublicKeyParameters sigPub;

    private byte[] hash;
    private String hexHash;

    @Setter
    private byte[] appData;


//    private final Transport transport;

    /**
     * Load a private key into the instance.
     *
     * @param privateKey The private key as *bytes*.
     * @return True if the key was loaded, otherwise False.
     */
    private boolean loadPrivateKey(final byte[] privateKey) {
        try {
            this.prvBytes = Arrays.copyOfRange(privateKey, 0, KEYSIZE / 8 / 2);
            this.prv = new X25519PrivateKeyParameters(prvBytes);
            this.pub = prv.generatePublicKey();
            this.pubBytes = pub.getEncoded();

            this.sigPrvBytes = Arrays.copyOfRange(privateKey, KEYSIZE / 8 / 2, privateKey.length);
            this.sigPrv = new Ed25519PrivateKeyParameters(sigPrvBytes);
            this.sigPub = sigPrv.generatePublicKey();
            this.sigPubBytes = sigPub.getEncoded();

            updateHashes();

            return true;
        } catch (Exception e) {
            log.error("Failed to load identity key.", e);
        }

        return false;
    }

    /**
     * Load a public key into the instance.
     *
     * @param publicKey The public key.
     * @return true if the key was loaded, otherwise false.
     */
    public boolean loadPublicKey(@NonNull final byte[] publicKey) {
        try {
            pubBytes = Arrays.copyOfRange(publicKey, 0, KEYSIZE / 8 / 2);
            sigPubBytes = Arrays.copyOfRange(publicKey, KEYSIZE / 8 / 2, publicKey.length);

            pub = new X25519PublicKeyParameters(pubBytes);
            sigPub = new Ed25519PublicKeyParameters(sigPubBytes);

            updateHashes();

            return true;
        } catch (Exception e) {
            log.error("Error while loading public key.", e);
        }

        return false;
    }

    /**
     * Signs information by the identity.
     *
     * @param message The message to be signed
     * @return Signature
     */
    public byte[] sign(byte[] message) {
        if (nonNull(sigPrv)) {
            try {
                var signer = new Ed25519Signer();
                signer.init(true, sigPrv);
                signer.update(message, 0, message.length);

                return signer.generateSignature();
            } catch (Exception e) {
                log.error("The identity {} could not sign the requested message.", this, e);
                throw e;
            }
        } else {
            throw new IllegalStateException("Signing failed because identity does not hold a private key");
        }
    }

    /**
     * Validates the signature of a signed message.
     *
     * @param signature The signature to be validated.
     * @param message The message to be validated.
     * @return <strong>true</strong> if the signature is valid, otherwise <strong>false</strong>
     */
    public boolean validate(byte[] signature, byte[] message) {
        if (nonNull(sigPub)) {
            try {
                var verifier = new Ed25519Signer();
                verifier.init(false, sigPub);
                verifier.update(message, 0, message.length);

                return verifier.verifySignature(signature);
            } catch (Exception e) {
                log.error("Error while validate ");

                return false;
            }
        } else {
            throw new IllegalStateException("Signature validation failed because identity does not hold a public key");
        }
    }

    /**
     * Encrypts information for the identity.
     *
     * @param plaintext The plaintext to be encrypted
     * @return Ciphertext token
     */
    public byte[] encrypt(final byte[] plaintext) {
        if (isNull(pub)) {
            throw new IllegalStateException("Encryption failed because identity does not hold a public key");
        }

        var ephemeralKeyPair = new X25519KeyPairGenerator().generateKeyPair();
        var ephemeralKey = (X25519PrivateKeyParameters) ephemeralKeyPair.getPrivate();
        var ephemeralPubBytes = ephemeralKey.generatePublicKey().getEncoded();

        var agreement = new X25519Agreement();
        agreement.init(ephemeralKey);
        var sharedKey = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(pub, sharedKey, 0);

        var hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(sharedKey, getSalt(), getContext()));
        var derivedKey = new byte[32];
        hkdf.generateBytes(derivedKey, 0, derivedKey.length);

        var fernet = new Fernet(derivedKey);
        var ciphertext = fernet.encrypt(plaintext);

        return ArrayUtils.addAll(ephemeralPubBytes, ciphertext);
    }

    public byte[] decrypt(final byte[] cipherTextToken) {
        if (isNull(prv)) {
            throw new IllegalStateException("Encryption failed because identity does not hold a public key");
        }

        if (cipherTextToken.length > KEYSIZE / 8 / 2) {
            byte[] plainText = null;
            try {
                var peerPubBytes = Arrays.copyOfRange(cipherTextToken, 0, KEYSIZE / 8 / 2);
                var peerPub = new X25519PublicKeyParameters(peerPubBytes);

                var agreement = new X25519Agreement();
                agreement.init(prv);
                var sharedKey = new byte[agreement.getAgreementSize()];
                agreement.calculateAgreement(peerPub, sharedKey, 0);

                var hkdf = new HKDFBytesGenerator(new SHA256Digest());
                hkdf.init(new HKDFParameters(sharedKey, getSalt(), getContext()));
                var derivedKey = new byte[32];
                hkdf.generateBytes(derivedKey, 0, derivedKey.length);

                var fernet = new Fernet(derivedKey);
                var cipherText = Arrays.copyOfRange(cipherTextToken, KEYSIZE / 8 / 2, cipherTextToken.length);
                plainText = fernet.decrypt(cipherText);
            } catch (Exception e) {
                log.debug("Decryption by {} failed.", hash, e);
            }

            return plainText;
        } else {
            log.debug("Decryption failed because the token size was invalid.");

            return null;
        }
    }

    /**
     * Create a new {@link Identity} instance from <strong>bytes</strong> of private key.
     * Can be used to load previously created and saved identities into Reticulum.
     *
     * @param bytes The *bytes* of private a saved private key. <strong>HAZARD!</strong> Never use this to generate a new key by feeding random data in prv_bytes.
     * @return A {@link Identity} instance, or <strong>null</strong> if the *bytes* data was invalid.
     */
    public static Identity fromBytes(byte[] bytes) {
        var identity = new Identity(false);
        if (identity.loadPrivateKey(bytes)) {
            return identity;
        }

        return null;
    }


    /**
     * Create a new {@link Identity} instance from a file.
     * Can be used to load previously created and saved identities into Reticulum.
     *
     * @param path The full path to the saved {@link Identity} data
     * @return A {@link Identity} instance, or <strong>null</strong> if the loaded data was invalid
     */
    public static Identity fromFile(Path path) {
        try {
            return fromBytes(Files.readAllBytes(path));
        } catch (IOException e) {
            log.error("Error while loading identity from {}", path, e);

            return null;
        }
    }

    private void updateHashes() {
        this.hash = truncatedHash(getPublicKey());
        this.hexHash = Hex.encodeHexString(hash);
    }

    public byte[] getPublicKey() {
        return ArrayUtils.addAll(pubBytes, sigPubBytes);
    }

    public byte[] getPrivateKey() {
        return ArrayUtils.addAll(prvBytes, sigPrvBytes);
    }

    public byte[] getSalt() {
        return hash;
    }

    public byte[] getContext() {
        return null;
    }

    private static class Fernet extends Key {

        private final IvParameterSpec initializationVector;

        public Fernet(byte[] concatenatedKeys) {
            super(concatenatedKeys);

            var secureRandom = new SecureRandom();
            final byte[] retval = new byte[16];
            secureRandom.nextBytes(retval);

            initializationVector = new IvParameterSpec(retval);
        }

        public byte[] encrypt(byte[] plainText) {
            return encrypt(plainText, initializationVector);
        }

        public byte[] decrypt(byte[] cipherText) {
           return decrypt(cipherText, initializationVector);
        }
    }
}
