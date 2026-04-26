package io.reticulum.identity;

import io.reticulum.Transport;
import io.reticulum.cryptography.Fernet;
import io.reticulum.destination.AbstractDestination;
import io.reticulum.destination.Destination;
import io.reticulum.packet.Packet;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ArrayUtils;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.util.Arrays;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static io.reticulum.constant.IdentityConstant.KEYSIZE;
import static io.reticulum.constant.IdentityConstant.NAME_HASH_LENGTH;
import static io.reticulum.constant.IdentityConstant.RATCHETSIZE;
import static io.reticulum.constant.IdentityConstant.RATCHET_EXPIRY;
import static io.reticulum.packet.PacketType.PROOF;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static io.reticulum.utils.IdentityUtils.fullHash;
import static io.reticulum.utils.IdentityUtils.truncatedHash;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.ArrayUtils.subarray;

/**
 * This class is used to manage identities in Reticulum. It provides methods
 * for encryption, decryption, signatures and verification, and is the basis
 * for all encrypted communication over Reticulum networks.
 */
@Slf4j
@ToString
@Getter
public class Identity {

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

    // ── Static ratchet state ──────────────────────────────────────────────────

    /** In-memory cache: hex(destinationHash) → ratchet public bytes received via announce. */
    private static final Map<String, byte[]> knownRatchets = new ConcurrentHashMap<>();

    /** Guards file-system writes for inbound ratchets. */
    private static final ReentrantLock ratchetPersistLock = new ReentrantLock();

    // ── Static ratchet API ────────────────────────────────────────────────────

    /**
     * Generates a new X25519 ratchet key and returns its raw private bytes (32 bytes).
     *
     * @return A newly generated randomized set of identity secret material.
     */
    public static byte[] generateRatchet() {
        return new X25519PrivateKeyParameters(new SecureRandom()).getEncoded();
    }

    /**
     * Derives the public bytes (32 bytes) from a ratchet private key.
     *
     * @param ratchetPrvBytes The input array containing both parts
     * @return Public part derived by applying ECDH with our own ephemeral
     */
    public static byte[] getRatchetPublicBytes(byte[] ratchetPrvBytes) {
        return new X25519PrivateKeyParameters(ratchetPrvBytes, 0).generatePublicKey().getEncoded();
    }

    /**
     * Computes the ratchet ID: truncated full-hash of the ratchet public bytes,
     * matching Python's {@code Identity._get_ratchet_id()}.
     *
     * @param ratchetPubBytes Raw output returned by either
     * @return rachetID
     */
    public static byte[] getRatchetId(byte[] ratchetPubBytes) {
        return subarray(fullHash(ratchetPubBytes), 0, NAME_HASH_LENGTH / 8);
    }

    /**
     * Returns the ID of the currently known ratchet for {@code destinationHash},
     * or {@code null} if none is known.
     *
     * @param destinationHash Hash identifying some Destinations
     * @return current racthhet ID, possibly cached locally!
     */
    public static byte[] getCurrentRatchetId(byte[] destinationHash) {
        byte[] ratchet = getRatchet(destinationHash);
        return ratchet != null ? getRatchetId(ratchet) : null;
    }

    /**
     * Stores a ratchet public key received in an announce.
     * Persists to {@code storagePath/ratchets/<hexhash>} asynchronously.
     *
     * @param destinationHash the destination the ratchet belongs to
     * @param ratchetPubBytes the 32-byte public key from the announce
     */
    public static void rememberRatchet(byte[] destinationHash, byte[] ratchetPubBytes) {
        String key = Hex.encodeHexString(destinationHash);
        byte[] existing = knownRatchets.get(key);
        if (java.util.Arrays.equals(existing, ratchetPubBytes)) {
            return; // already known — nothing to do
        }

        log.debug("Remembering ratchet {} for {}",
                Hex.encodeHexString(getRatchetId(ratchetPubBytes)), key);
        knownRatchets.put(key, ratchetPubBytes);

        try {
            if (!Transport.getInstance().getOwner().isConnectedToSharedInstance()) {
                Thread persistThread = new Thread(() -> {
                    ratchetPersistLock.lock();
                    try {
                        Path ratchetDir = Transport.getInstance().getOwner()
                                .getStoragePath().resolve("ratchets");
                        Files.createDirectories(ratchetDir);

                        // Format: 8-byte big-endian epoch-seconds + 32-byte ratchet pub bytes
                        byte[] data = ByteBuffer.allocate(8 + ratchetPubBytes.length)
                                .putLong(Instant.now().getEpochSecond())
                                .put(ratchetPubBytes)
                                .array();

                        Path outPath   = ratchetDir.resolve(key + ".tmp");
                        Path finalPath = ratchetDir.resolve(key);
                        Files.write(outPath, data, CREATE, WRITE, TRUNCATE_EXISTING);
                        Files.move(outPath, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception e) {
                        log.error("Could not persist ratchet for {} to storage.", key, e);
                    } finally {
                        ratchetPersistLock.unlock();
                    }
                });
                persistThread.setDaemon(true);
                persistThread.start();
            }
        } catch (Exception e) {
            log.debug("Skipping ratchet persistence: {}", e.getMessage());
        }
    }

    /**
     * Returns the ratchet public bytes for {@code destinationHash} if known
     * (in memory or on disk), or {@code null} if none is available or the stored
     * ratchet has expired.
     *
     * @param destinationHash Identifies one specific remote peer
     * @return Current local copy of that peers' shared symmetric session
     */
    public static byte[] getRatchet(byte[] destinationHash) {
        String key = Hex.encodeHexString(destinationHash);
        if (knownRatchets.containsKey(key)) {
            return knownRatchets.get(key);
        }

        // Try loading from disk
        try {
            Path ratchetDir = Transport.getInstance().getOwner()
                    .getStoragePath().resolve("ratchets");
            Path ratchetPath = ratchetDir.resolve(key);
            if (Files.isRegularFile(ratchetPath)) {
                byte[] data = Files.readAllBytes(ratchetPath);
                if (data.length == 8 + RATCHETSIZE / 8) {
                    long received = ByteBuffer.wrap(data, 0, 8).getLong();
                    if (Instant.now().getEpochSecond() < received + RATCHET_EXPIRY) {
                        byte[] ratchet = subarray(data, 8, data.length);
                        knownRatchets.put(key, ratchet);
                        return ratchet;
                    }
                }
                // Expired or corrupt — remove
                Files.deleteIfExists(ratchetPath);
            }
        } catch (Exception e) {
            log.debug("Could not load ratchet for {} from storage: {}", key, e.getMessage());
        }

        return null;
    }

    /**
     * Removes ratchet files older than {@link io.reticulum.constant.IdentityConstant#RATCHET_EXPIRY}
     * from the storage directory.
     */
    public static void cleanRatchets() {
        log.debug("Cleaning ratchets...");
        try {
            Path ratchetDir = Transport.getInstance().getOwner()
                    .getStoragePath().resolve("ratchets");
            if (!Files.isDirectory(ratchetDir)) return;

            long now = Instant.now().getEpochSecond();
            Files.list(ratchetDir).forEach(path -> {
                try {
                    byte[] data = Files.readAllBytes(path);
                    boolean expired  = data.length == 8 + RATCHETSIZE / 8
                            && now > ByteBuffer.wrap(data, 0, 8).getLong() + RATCHET_EXPIRY;
                    boolean corrupt  = data.length != 8 + RATCHETSIZE / 8;
                    if (expired || corrupt) {
                        Files.deleteIfExists(path);
                    }
                } catch (Exception e) {
                    log.error("Error cleaning ratchet file {}: {}", path, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Error while cleaning ratchets: {}", e.getMessage());
        }
    }

    // ── Constructors ──────────────────────────────────────────────────────────

    public Identity() {
        this(true);
    }

    public Identity(boolean createKeys) {
        if (createKeys) {
            prv = new X25519PrivateKeyParameters(new SecureRandom());
            prvBytes = prv.getEncoded();

            sigPrv = new Ed25519PrivateKeyParameters(new SecureRandom());
            sigPrvBytes = sigPrv.getEncoded();

            pub = prv.generatePublicKey();
            pubBytes = pub.getEncoded();

            sigPub = sigPrv.generatePublicKey();
            sigPubBytes = sigPub.getEncoded();

            updateHashes();

            log.info("Identity keys created for {}", hash);
        }
    }

    /**
     * Load a private key into the instance.
     *
     * @param privateKey The private key as *bytes*.
     * @return True if the key was loaded, otherwise False.
     */
    private boolean loadPrivateKey(final byte[] privateKey) {
        try {
            this.prvBytes = subarray(privateKey, 0, KEYSIZE / 8 / 2);
            this.prv = new X25519PrivateKeyParameters(new ByteArrayInputStream(getPrvBytes()));
            this.pub = prv.generatePublicKey();
            this.pubBytes = pub.getEncoded();

            this.sigPrvBytes = subarray(privateKey, KEYSIZE / 8 / 2, privateKey.length);
            this.sigPrv = new Ed25519PrivateKeyParameters(new ByteArrayInputStream(getSigPrvBytes()));
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
            pubBytes = subarray(publicKey, 0, KEYSIZE / 8 / 2);
            sigPubBytes = subarray(publicKey, KEYSIZE / 8 / 2, publicKey.length);

            pub = new X25519PublicKeyParameters(pubBytes, 0);
            sigPub = new Ed25519PublicKeyParameters(sigPubBytes, 0);

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
        if (nonNull(pub)) {
            try {
                var verifier = new Ed25519Signer();
                verifier.init(false, sigPub);
                verifier.update(message, 0, message.length);

                var validationResult = verifier.verifySignature(signature);
                //log.info("bba - Identity.validate - verifier: {}, sigPub: {}, validationResult: *{}*", verifier, sigPub, validationResult);
                return validationResult;

            } catch (Exception e) {
                log.error("Error while validate ");

                return false;
            }
        } else {
            throw new IllegalStateException("Signature validation failed because identity does not hold a public key");
        }
    }

    // ── Encryption / decryption ───────────────────────────────────────────────

    /**
     * Encrypts information for the identity, optionally using a ratchet public key
     * instead of the identity's own public key as the ECDH target.
     *
     * @param plaintext       The plaintext to be encrypted.
     * @param ratchetPubBytes 32-byte X25519 ratchet public key, or {@code null} to use
     *                        the identity's base public key.
     * @return Ciphertext token (ephemeral-pub || fernet-ciphertext).
     */
    @SneakyThrows
    public byte[] encrypt(final byte[] plaintext, final byte[] ratchetPubBytes) {
        X25519PublicKeyParameters targetPub;
        if (ratchetPubBytes != null) {
            targetPub = new X25519PublicKeyParameters(ratchetPubBytes, 0);
        } else {
            if (isNull(pub)) {
                throw new IllegalStateException("Encryption failed because identity does not hold a public key");
            }
            targetPub = pub;
        }

        var ephemeralKey     = new X25519PrivateKeyParameters(new SecureRandom());
        var ephemeralPubBytes = ephemeralKey.generatePublicKey().getEncoded();

        var agreement = new X25519Agreement();
        agreement.init(ephemeralKey);
        var sharedKey = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(targetPub, sharedKey, 0);

        var hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(sharedKey, getSalt(), getContext()));
        var derivedKey = new byte[32];
        hkdf.generateBytes(derivedKey, 0, derivedKey.length);

        var fernet     = new Fernet(derivedKey);
        var ciphertext = fernet.encrypt(plaintext);

        return concatArrays(ephemeralPubBytes, ciphertext);
    }

    /**
     * Encrypts information for the identity.
     *
     * @param plaintext The plaintext to be encrypted
     * @return Ciphertext token
     */
    @SneakyThrows
    public byte[] encrypt(final byte[] plaintext) {
        if (isNull(pub)) {
            throw new IllegalStateException("Encryption failed because identity does not hold a public key");
        }

        var ephemeralKey = new X25519PrivateKeyParameters(new SecureRandom());
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

        return concatArrays(ephemeralPubBytes, ciphertext);
    }

    public byte[] decrypt(final byte[] cipherTextToken) {
        if (isNull(prv)) {
            throw new IllegalStateException("Encryption failed because identity does not hold a public key");
        }

        if (cipherTextToken.length > KEYSIZE / 8 / 2) {
            byte[] plainText = null;
            try {
                var peerPubBytes = subarray(cipherTextToken, 0, KEYSIZE / 8 / 2);
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
                var cipherText = subarray(cipherTextToken, KEYSIZE / 8 / 2, cipherTextToken.length);
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
     * Decrypts a ciphertext token, trying each supplied ratchet (private key bytes)
     * before falling back to the identity's own private key.
     *
     * @param cipherTextToken  The ciphertext token.
     * @param ratchets         List of ratchet private key bytes to try (newest first).
     *                         May be {@code null} or empty to skip ratchet attempts.
     * @param enforceRatchets  If {@code true}, return {@code null} when all ratchets
     *                         fail rather than falling back to the base key.
     * @return Decrypted plaintext, or {@code null} on failure.
     */
    public byte[] decrypt(final byte[] cipherTextToken, final List<byte[]> ratchets, final boolean enforceRatchets) {
        if (cipherTextToken.length <= KEYSIZE / 8 / 2) {
            log.debug("Decryption failed because the token size was invalid.");
            return null;
        }

        var peerPubBytes = subarray(cipherTextToken, 0, KEYSIZE / 8 / 2);
        var ciphertext   = subarray(cipherTextToken, KEYSIZE / 8 / 2, cipherTextToken.length);

        // Try each ratchet (private bytes) first
        if (ratchets != null) {
            for (byte[] ratchetPrvBytes : ratchets) {
                byte[] result = decryptWithPrivateKey(ratchetPrvBytes, peerPubBytes, ciphertext);
                if (result != null) {
                    return result;
                }
            }
        }

        if (enforceRatchets) {
            log.debug("Ratchet-enforced decryption by {} failed — dropping packet.", Hex.encodeHexString(hash));
            return null;
        }

        // Fall back to identity's base private key
        return decrypt(cipherTextToken);
    }

    /**
     * Shared ECDH + HKDF + Fernet decryption helper.
     * Returns plaintext on success, {@code null} on any failure.
     */
    private byte[] decryptWithPrivateKey(byte[] prvBytes, byte[] peerPubBytes, byte[] ciphertext) {
        try {
            var ratchetPrv = new X25519PrivateKeyParameters(prvBytes, 0);
            var peerPub    = new X25519PublicKeyParameters(peerPubBytes, 0);

            var agreement = new X25519Agreement();
            agreement.init(ratchetPrv);
            var sharedKey = new byte[agreement.getAgreementSize()];
            agreement.calculateAgreement(peerPub, sharedKey, 0);

            var hkdf = new HKDFBytesGenerator(new SHA256Digest());
            hkdf.init(new HKDFParameters(sharedKey, getSalt(), getContext()));
            var derivedKey = new byte[32];
            hkdf.generateBytes(derivedKey, 0, derivedKey.length);

            return new Fernet(derivedKey).decrypt(ciphertext);
        } catch (Exception e) {
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

    /**
     * Save identity to file
     * @param path The full path to the file
     * @return Whether saving succeeded
     * @throws IOException When something goes wrong with writing to disc
     */
    public boolean toFile(Path path) throws IOException {
        var privateKeyBytes = Arrays.concatenate(prvBytes, sigPrvBytes);
        return Files.write(path, privateKeyBytes, WRITE, CREATE).toFile().exists();
    }

    @SneakyThrows
    public void prove(@NonNull final Packet packet, final AbstractDestination destination) {
        byte [] proofData;
        Destination dest = (Destination) destination;
        var signature = sign(packet.getPacketHash());
        var shouldUseImplicitProof = Transport.getInstance().getOwner().isUseImplicitProof();
        if (shouldUseImplicitProof) {
            proofData = signature;
        } else {
            proofData = concatArrays(packet.getPacketHash(), signature);
        }

        if (isNull(dest)) {
            dest = packet.generateProofDestination();
        }
        var proof = new Packet(
                dest,
                proofData,
                PROOF,
                packet.getReceivingInterface()
        );
        proof.send();
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
}
