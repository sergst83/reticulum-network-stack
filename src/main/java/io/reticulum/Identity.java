package io.reticulum;

import io.reticulum.utils.IdentityUtils;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ArrayUtils;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.util.Arrays;

import static java.util.Objects.nonNull;

/**
 * This class is used to manage identities in Reticulum. It provides methods
 * for encryption, decryption, signatures and verification, and is the basis
 * for all encrypted communication over Reticulum networks.
 */
@Builder
@Slf4j
@ToString
@RequiredArgsConstructor
public class Identity implements PersistData, ExitHandler {

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


//    private final Transport transport;

    /**
     * Load a private key into the instance.
     *
     * @param bytes The private key as *bytes*.
     * @return True if the key was loaded, otherwise False.
     */
    private boolean loadPrivateKey(final byte[] bytes) {
        try {
            this.prvBytes = Arrays.copyOfRange(bytes, 0, KEYSIZE / 8 / 2);
            this.prv = new X25519PrivateKeyParameters(prvBytes);
            this.pub = prv.generatePublicKey();
            this.pubBytes = pub.getEncoded();

            this.sigPrvBytes = Arrays.copyOfRange(bytes, KEYSIZE / 8 / 2, bytes.length);
            this.sigPrv = new Ed25519PrivateKeyParameters(sigPrvBytes);
            this.sigPub = sigPrv.generatePublicKey();
            this.sigPubBytes = sigPub.getEncoded();

            updateHashes();

            return true;
        } catch (Exception e) {
            log.error("Failed to load identity key.", e);

            return false;
        }
    }

    //todo додулать это
    @Override
    public void persistData() {
//        if (transport.getOwner().isConnectedToSharedInstance()) {

//        }
    }

    // TODO: 18.02.2023 доделать реализацию
    @Override
    public void exitHandler() {

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
     * Create a new :ref:`RNS.Identity<api-identity>` instance from *bytes* of private key.
     * Can be used to load previously created and saved identities into Reticulum.
     *
     * @param bytes The *bytes* of private a saved private key. **HAZARD!** Never use this to generate a new key by feeding random data in prv_bytes.
     * @return A {@link Identity} instance, or *null* if the *bytes* data was invalid.
     */
    public static Identity fromBytes(byte[] bytes) {
        var identity = new Identity(false);
        if (identity.loadPrivateKey(bytes)) {
            return identity;
        }

        return null;
    }

    private void updateHashes() {
        this.hash = IdentityUtils.truncatedHash(get_public_key());
        this.hexHash = Hex.encodeHexString(hash);
    }

    private byte[] get_public_key() {
        return ArrayUtils.addAll(pubBytes, sigPubBytes);
    }
}
