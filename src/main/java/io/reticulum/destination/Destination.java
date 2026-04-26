package io.reticulum.destination;

import com.fasterxml.jackson.core.type.TypeReference;
import io.reticulum.Transport;
import io.reticulum.cryptography.Fernet;
import io.reticulum.identity.Identity;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import io.reticulum.packet.PacketContextType;
import io.reticulum.packet.PacketType;
import io.reticulum.utils.DestinationUtils;
import io.reticulum.utils.IdentityUtils;
import io.reticulum.utils.LinkUtils;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.msgpack.jackson.dataformat.MessagePackMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.reticulum.constant.DestinationConstant.PR_TAG_WINDOW;
import static io.reticulum.constant.IdentityConstant.NAME_HASH_LENGTH;
import static io.reticulum.destination.DestinationType.*;
import static io.reticulum.destination.Direction.IN;
import static io.reticulum.destination.ProofStrategy.PROVE_NONE;
import static io.reticulum.packet.ContextType.FLAG_SET;
import static io.reticulum.packet.ContextType.FLAG_UNSET;
import static io.reticulum.packet.PacketContextType.NONE;
import static io.reticulum.packet.PacketContextType.PATH_RESPONSE;
import static io.reticulum.packet.PacketType.ANNOUNCE;
import static io.reticulum.utils.CommonUtils.longToByteArray;
import static io.reticulum.utils.DestinationUtils.expandName;
import static io.reticulum.utils.IdentityUtils.*;
import static java.lang.System.currentTimeMillis;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.*;
import static java.util.Objects.*;
import static org.apache.commons.lang3.ArrayUtils.subarray;

/**
 * A class used to describe endpoints in a Reticulum Network. Destination
 * instances are used both to create outgoing and incoming endpoints. The
 * destination type will decide if encryption, and what type, is used in
 * communication with the endpoint. A destination can also announce its
 * presence on the network, which will also distribute necessary keys for
 * encrypted communication with it.
 */
@Getter
@Setter
@Slf4j
@NoArgsConstructor
public class Destination extends AbstractDestination {

    // ── Ratchet constants ─────────────────────────────────────────────────────

    /** Default number of ratchet keys retained for decryption. */
    public static final int RATCHET_COUNT    = 512;

    /** Default minimum interval between ratchet rotations, in seconds (30 minutes). */
    public static final int RATCHET_INTERVAL = 30 * 60;

    // ── Ratchet state ─────────────────────────────────────────────────────────

    /** Locally-generated ratchet private keys (newest first). {@code null} = ratchets not enabled. */
    private List<byte[]> ratchets = null;

    private Path ratchetsPath = null;

    private int ratchetInterval = RATCHET_INTERVAL;

    private int retainedRatchets = RATCHET_COUNT;

    /** Epoch-second timestamp of the last ratchet rotation. */
    private long latestRatchetTime = 0;

    /**
     * ID (truncated hash of public key) of the ratchet that successfully
     * decrypted the last incoming packet, or {@code null} if the base key was used.
     */
    private byte[] latestRatchetId = null;

    private boolean enforceRatchetsEnabled = false;

    /** Guards ratchet file I/O. Not exposed as a Lombok setter (field is final). */
    private final ReentrantLock ratchetFileLock = new ReentrantLock();

    private static final MessagePackMapper RATCHET_MSGPACK = new MessagePackMapper();

    // ── Identity ──────────────────────────────────────────────────────────────

    private Identity identity;
    private byte[] hash;
    private String hexHash;
    private boolean acceptLinkRequests = true;
    private DestinationCallbacks callbacks = new DestinationCallbacks();
    private Map<String, RequestHandler> requestHandlers = new ConcurrentHashMap<>();
    private DestinationType type;
    private Direction direction;
    private ProofStrategy proofStrategy = PROVE_NONE;
    private int mtu = 0;
    private Map<String, Pair<Instant, byte[]>> pathResponses = new ConcurrentHashMap<>();
    private List<Link> links = new CopyOnWriteArrayList<>();
    private String name;
    private byte[] nameHash;
    private byte[] defaultAppData;
    private Object callback;
    private Object proofcallback;
    private Fernet prv;
    private byte[] prvBytes;

    @SneakyThrows
    public Destination(Identity identity, Direction direction, DestinationType type, String appName, String... aspects) {
        // Check input values and build name string
        if (StringUtils.contains(appName, '.')) {
            throw new IllegalArgumentException("Dots can't be used in app names");
        }

        this.type = type;
        this.direction = direction;

        var aspectsLocal = new ArrayList<>(List.of(aspects));
        if (isNull(identity) && direction == IN && type != PLAIN) {
            identity = new Identity();
            aspectsLocal.add(this.identity.getHexHash());
        }
        if (nonNull(identity) && type == PLAIN) {
            throw new IllegalStateException("Selected destination type PLAIN cannot hold an identity");
        }
        this.identity = identity;

        var arrayLocalAspects = aspectsLocal.toArray(String[]::new);
        this.name = expandName(this.identity, appName, arrayLocalAspects);

        // Generate the destination address hash
        this.hash = DestinationUtils.hash(this.identity, appName, arrayLocalAspects);
        this.nameHash = subarray(
                fullHash(expandName(null, appName, arrayLocalAspects).getBytes(UTF_8)),
                0,
                NAME_HASH_LENGTH / 8
        );
        this.hexHash = Hex.encodeHexString(this.hash);
        Transport.getInstance().registerDestination(this);
    }

    /**
     * Set or query whether the destination accepts incoming link requests.
     *
     * @param accepts if <strong>true</strong> or <strong>false</strong>, this method sets whether the destination accepts incoming link requests.
     *                If null, the method returns whether the destination currently accepts link requests.
     * @return <strong>true</strong> or <strong>false</strong> depending on whether the destination accepts incoming link requests, if the <strong>accepts</strong> parameter is null.
     */
    public boolean acceptsLinks(Boolean accepts) {
        if (isNull(accepts)) {
            return acceptLinkRequests;
        }

        acceptLinkRequests = accepts;

        return acceptLinkRequests;
    }

    /**
     * Registers a function to be called when a link has been established to this destination.
     *
     * @param callback A function or method to be called when a new link is established with this destination.
     */
    public void setLinkEstablishedCallback(Consumer<Link> callback) {
        this.callbacks.setLinkEstablished(callback);
    }

    /**
     * Registers a function to be called when a packet has been received by this destination.
     *
     * @param callback A function or method with the signature <strong>callback(data, packet)</strong> to be called when this destination receives a packet
     */
    public void setPacketCallback(BiConsumer<byte[], Packet> callback) {
        this.callbacks.setPacket(callback);
    }

    /**
     * Registers a function to be called when a proof has been requested for a packet sent to this destination.
     * Allows control over when and if proofs should be returned for received packets.
     *
     * @param callback A function or method to with the signature <strong>callback(packet)</strong> be called when a packet that
     *                 requests a proof is received. The callback must return one of True or False. If the callback
     *                 returns True, a proof will be sent. If it returns False, a proof will not be sent.
     */
    public void setProofRequestedCallback(Function<Packet, Boolean> callback) {
        this.callbacks.setProofRequested(callback);
    }

    /**
     * Registers a request handler.
     *
     * @param path The path for the request handler to be registered.
     * @param responseGenerator Whatever this funcion returns will be sent as a response to the requester. If the function returns null, no response will be sent.
     * @param allow If <strong>ALLOW_LIST</strong> is set, the request handler will only respond to requests for identified peers in the supplied list.
     * @param allowedList A list of <strong>byte[]</strong> {@link Identity} hashes.
     */
    public void registerRequestHandler(
            @NonNull final String path,
            final Function<Request, byte[]> responseGenerator,
            final RequestPolicy allow,
            final List<byte[]> allowedList
    ) {
        if (path.isEmpty()) {
            throw new IllegalArgumentException("Invalid path specified");
        }

        requestHandlers.put(
                Hex.encodeHexString(truncatedHash(path.getBytes(UTF_8))),
                new RequestHandler(path, responseGenerator, allow, allowedList)
        );
    }

    /**
     * Deregisters a request handler.
     *
     * @param path The path for the request handler to be deregistered.
     * @return true if the handler was deregistered, otherwise false.
     */
    public boolean deregisterRequestHandler(@NonNull final String path) {
        var pathHash = IdentityUtils.truncatedHash(path.getBytes(UTF_8));

        return nonNull(requestHandlers.remove(Hex.encodeHexString(pathHash)));
    }

    public void receive(@NonNull final Packet packet) {
        if (packet.getPacketType() == PacketType.LINKREQUEST) {
            incomingLinkRequest(packet);
        } else {
            var plainText = decrypt(packet.getData());
            if (nonNull(plainText) && packet.getPacketType() == PacketType.DATA && nonNull(callbacks.getPacket())) {
                try {
                    callbacks.getPacket().accept(plainText, packet);
                } catch (Exception e) {
                    log.error("Error while executing receive callback from {}.", this);
                }
            }
        }
    }

    /**
     * For a DestinationType.GROUP type destination, creates a new symmetric key.
     */
    public void createKeys() {
        if (type == GROUP) {
            prvBytes = Fernet.generateFernetKey();
            prv = new Fernet(prvBytes);
        } else {
            throw new IllegalStateException("Only for DestinationType.GROUP");
        }
    }

    /**
     * For a DestinationType.GROUP type destination, returns the symmetric private key.
     *
     * @return {@link Fernet} key as byte[]
     */
    public byte[] getPrivateKey() {
        if (type == GROUP) {
            return prvBytes;
        } else {
            throw new IllegalStateException("Only for DestinationType.GROUP");
        }
    }

    /**
     * For a DestinationType.GROUP type destination, loads a symmetric private key.
     *
     * @param key containing the symmetric key.
     */
    public void loadPrivateKey(@NonNull byte[] key) {
        if (type == GROUP) {
            this.prvBytes = key;
            this.prv = new Fernet(this.prvBytes);
        } else {
            throw new IllegalStateException("Only for DestinationType.GROUP");
        }
    }

    /**
     * Encrypts information for DestinationType.SINGLE or DestinationType.GROUP type destination.
     *
     * @param plaintext containing the plaintext to be encrypted
     * @return encrypted
     */
    public byte[] encrypt(byte[] plaintext) {
        switch (type) {
            case PLAIN:
                return plaintext;
            case SINGLE:
                if (nonNull(identity)) {
                    // Use any ratchet key that was announced by the remote destination
                    byte[] knownRatchet = Identity.getRatchet(hash);
                    if (knownRatchet != null) {
                        latestRatchetId = Identity.getRatchetId(knownRatchet);
                    } else {
                        latestRatchetId = null;
                    }
                    return identity.encrypt(plaintext, knownRatchet);
                }
                break;
            case GROUP:
                if (nonNull(prv)) {
                    try {
                        return prv.encrypt(plaintext);
                    } catch (IOException e) {
                        log.error("The GROUP destination could not encrypt data.", e);
                    }
                } else {
                    throw new IllegalStateException("No private key held by GROUP destination. Did you create or load one?");
                }
                break;
            default:
                return null;
        }

        return null;
    }

    /**
     * Decrypts information for DestinationType.SINGLE or DestinationType.GROUP type destination.
     *
     * @param ciphertext ciphertext: byte[] containing the ciphertext to be decrypted.
     * @return decrypted
     */
    public byte[] decrypt(byte[] ciphertext) {
        switch (type) {
            case PLAIN:
                return ciphertext;
            case SINGLE:
                if (nonNull(identity)) {
                    if (ratchets != null) {
                        // Try own ratchets (private keys, newest first)
                        byte[] plaintext = identity.decrypt(ciphertext, ratchets, enforceRatchetsEnabled);
                        if (plaintext == null && !enforceRatchetsEnabled) {
                            // Ratchet decryption failed — reload from disk and retry once
                            log.error("Decryption with ratchets failed on {}, reloading ratchets and retrying", this);
                            try {
                                reloadRatchets(ratchetsPath);
                                plaintext = identity.decrypt(ciphertext, ratchets, enforceRatchetsEnabled);
                                if (plaintext != null) {
                                    log.info("Decryption succeeded after ratchet reload on {}", this);
                                }
                            } catch (Exception e) {
                                log.error("Decryption still failing after ratchet reload on {}", this, e);
                            }
                        }
                        return plaintext;
                    }
                    return identity.decrypt(ciphertext);
                }
                break;
            case GROUP:
                if (nonNull(prv)) {
                    try {
                        return prv.decrypt(ciphertext);
                    } catch (Exception e) {
                        log.error("The GROUP destination could not decrypt data.", e);
                    }
                } else {
                    throw new IllegalStateException("No private key held by GROUP destination. Did you create or load one?");
                }
                break;
            default:
                return null;
        }

        return null;
    }

    /**
     * Signs information for DestinationType.SINGLE type destination.
     *
     * @param message byte[] containing the message to be signed.
     * @return A byte[] containing the message signature, or null if the destination could not sign the message.
     */
    public byte[] sign(@NonNull byte[] message) {
        if (type == SINGLE && nonNull(identity)) {
            return identity.sign(message);
        }

        return null;
    }

    // ── Ratchet public API ────────────────────────────────────────────────────

    /**
     * Enables ratchets on this destination.  Ratchets provide forward secrecy
     * for packets sent outside a Link by rotating the encryption key periodically.
     *
     * @param ratchetsPath path to the file used to persist ratchet data
     * @return {@code true} on success
     */
    public boolean enableRatchets(Path ratchetsPath) {
        if (ratchetsPath == null) {
            throw new IllegalArgumentException("No ratchet file path specified for " + this);
        }
        this.latestRatchetTime = 0;
        reloadRatchets(ratchetsPath);
        log.debug("Ratchets enabled on {}", this);
        return true;
    }

    /**
     * When called, this destination will only accept packets encrypted with
     * one of its retained ratchet keys, rejecting anything encrypted with the
     * base identity key.
     *
     * @return {@code true} if ratchets are enabled; {@code false} otherwise
     */
    public boolean enforceRatchets() {
        if (ratchets != null) {
            enforceRatchetsEnabled = true;
            log.debug("Ratchets enforced on {}", this);
            return true;
        }
        return false;
    }

    /**
     * Rotates the ratchet key if the configured interval has elapsed.
     * Called automatically during each announce.
     *
     * @return {@code true} if rotation occurred or was skipped (not yet due)
     * @throws IllegalStateException if ratchets are not enabled
     */
    public boolean rotateRatchets() {
        if (ratchets == null) {
            throw new IllegalStateException("Cannot rotate ratchet on " + this + ", ratchets are not enabled");
        }
        long now = Instant.now().getEpochSecond();
        if (now > latestRatchetTime + ratchetInterval) {
            log.debug("Rotating ratchets for {}", this);
            byte[] newRatchet = Identity.generateRatchet();
            ratchets.add(0, newRatchet);
            latestRatchetTime = now;
            cleanRatchets();
            persistRatchets();
        }
        return true;
    }

    // ── Ratchet private helpers ───────────────────────────────────────────────

    private void cleanRatchets() {
        if (ratchets != null && ratchets.size() > retainedRatchets) {
            ratchets = new LinkedList<>(ratchets.subList(0, retainedRatchets));
        }
    }

    private void persistRatchets() {
        ratchetFileLock.lock();
        try {
            byte[] packedRatchets = RATCHET_MSGPACK.writeValueAsBytes(ratchets);
            byte[] signature      = identity.sign(packedRatchets);

            // Format: 4-byte sig-length + signature + ratchet list bytes
            byte[] data = ByteBuffer.allocate(4 + signature.length + packedRatchets.length)
                    .putInt(signature.length)
                    .put(signature)
                    .put(packedRatchets)
                    .array();

            Path tmpPath = Path.of(ratchetsPath.toString() + ".tmp");
            Files.write(tmpPath, data, CREATE, WRITE, TRUNCATE_EXISTING);
            Files.move(tmpPath, ratchetsPath, REPLACE_EXISTING);
        } catch (Exception e) {
            log.error("Could not persist ratchets for {}.", this, e);
            ratchets     = null;
            ratchetsPath = null;
        } finally {
            ratchetFileLock.unlock();
        }
    }

    private void reloadRatchets(Path ratchetsPath) {
        ratchetFileLock.lock();
        try {
            if (Files.isRegularFile(ratchetsPath)) {
                try {
                    loadRatchetFile(ratchetsPath);
                } catch (Exception e) {
                    log.error("First ratchet reload attempt for {} failed. Retrying in 500ms.", this, e);
                    try {
                        Thread.sleep(500);
                        loadRatchetFile(ratchetsPath);
                        log.debug("Ratchet reload retry succeeded for {}", this);
                    } catch (Exception e2) {
                        log.error("Ratchet file at {} could not be loaded for {}.", ratchetsPath, this, e2);
                        this.ratchets     = null;
                        this.ratchetsPath = null;
                        return;
                    }
                }
                this.ratchetsPath = ratchetsPath;
            } else {
                log.debug("No existing ratchet data found, initialising new ratchet file for {}", this);
                this.ratchets     = new LinkedList<>();
                this.ratchetsPath = ratchetsPath;
                persistRatchets();
            }
        } finally {
            ratchetFileLock.unlock();
        }
    }

    private void loadRatchetFile(Path ratchetsPath) throws IOException {
        byte[] data = Files.readAllBytes(ratchetsPath);
        if (data.length < 4) {
            throw new IOException("Ratchet file too short");
        }
        int sigLen           = ByteBuffer.wrap(data, 0, 4).getInt();
        byte[] signature     = Arrays.copyOfRange(data, 4, 4 + sigLen);
        byte[] packedRatchets = Arrays.copyOfRange(data, 4 + sigLen, data.length);

        if (!identity.validate(signature, packedRatchets)) {
            throw new SecurityException("Invalid ratchet file signature for " + this);
        }
        this.ratchets = RATCHET_MSGPACK.readValue(packedRatchets,
                new TypeReference<List<byte[]>>() {});
    }

    // ── Announce ──────────────────────────────────────────────────────────────

    public Packet announce() {
        return announce(null);
    }

    public Packet announce(byte[] appData) {
        return announce(appData, false, null, null, true);
    }

    public Packet announce(boolean pathResponse) {
        return announce(null, pathResponse, null, null, true);
    }

    public Packet announce(boolean pathResponse, byte[] tag, ConnectionInterface attachedInterface) {
        return announce(null, pathResponse, attachedInterface, tag, true);
    }

    /**
     * Creates an announce packet for this destination and broadcasts it on all
     * relevant interfaces. Application specific data can be added to the announce.
     *
     * @param appData      byte array containing the app_data
     * @param pathResponse Internal flag used by {@link Transport}. Ignore.
     * @param attachedInterface {@link ConnectionInterface} to attach
     * @param tag           Optional identifier string
     * @param send          Whether to actually transmit the created packaged
     * @return {@link Packet}
     */
    public Packet announce(
            final byte[] appData,
            final boolean pathResponse,
            final ConnectionInterface attachedInterface,
            final byte[] tag,
            final boolean send
    ) {
        var localAppData = nonNull(appData) ? Arrays.copyOf(appData, appData.length) : null;
        if (this.type != SINGLE) {
            throw new IllegalStateException("Only SINGLE destination types can be announced");
        }

        if (this.getDirection() != IN) {
            throw new IllegalStateException("Only IN destination types can be announced");
        }

        var staleResponses = new ArrayList<String>();
        var now = Instant.now();
        pathResponses.forEach((entryTag, entry) -> {
            if (now.isAfter(entry.getLeft().plusSeconds(PR_TAG_WINDOW))) {
                staleResponses.add(entryTag);
            }
        });

        staleResponses.forEach(pathResponses::remove);

        byte[] announceData;
        if (pathResponse && nonNull(tag) && pathResponses.containsKey(Hex.encodeHexString(tag))) {
            // This code is currently not used, since Transport will block duplicate
            // path requests based on tags. When multi-path support is implemented in
            // Transport, this will allow Transport to detect redundant paths to the
            // same destination, and select the best one based on chosen criteria,
            // since it will be able to detect that a single emitted announce was
            // received via multiple paths. The difference in reception time will
            // potentially also be useful in determining characteristics of the
            // multiple available paths, and to choose the best one.
            log.debug("Using cached announce data for answering path request with tag {}", Hex.encodeHexString(tag));
            announceData = pathResponses.get(Hex.encodeHexString(tag)).getRight();
        } else {
            var randomHash = concatArrays(
                    subarray(getRandomHash(), 0, 5),
                    longToByteArray(currentTimeMillis() / 1000, 5)
            );

            if (isNull(localAppData) && nonNull(defaultAppData)) {
                localAppData = defaultAppData;
            }

            // Ratchet: rotate if enabled, extract public bytes of the newest key
            byte[] ratchetPub = null;
            if (ratchets != null) {
                rotateRatchets();
                ratchetPub = Identity.getRatchetPublicBytes(ratchets.get(0));
                Identity.rememberRatchet(hash, ratchetPub);
            }

            // signed_data = hash || pubkey || nameHash || randomHash [|| ratchet] [|| appData]
            var signedData = concatArrays(hash, identity.getPublicKey(), nameHash, randomHash);
            if (ratchetPub != null) {
                signedData = concatArrays(signedData, ratchetPub);
            }
            if (nonNull(localAppData)) {
                signedData = concatArrays(signedData, localAppData);
            }

            var signature = identity.sign(signedData);

            // announce_data = pubkey || nameHash || randomHash [|| ratchet] || signature [|| appData]
            announceData = concatArrays(identity.getPublicKey(), nameHash, randomHash);
            if (ratchetPub != null) {
                announceData = concatArrays(announceData, ratchetPub);
            }
            announceData = concatArrays(announceData, signature);
            if (nonNull(localAppData)) {
                announceData = concatArrays(announceData, localAppData);
            }

            pathResponses.put(
                    Hex.encodeHexString(requireNonNullElse(tag, new byte[0])),
                    Pair.of(Instant.now(), announceData)
            );
        }

        PacketContextType announceContext;
        if (pathResponse) {
            announceContext = PATH_RESPONSE;
        } else {
            announceContext = NONE;
        }

        var announcePacket = new Packet(this, announceData, ANNOUNCE, announceContext, attachedInterface);

        // Set the context flag bit in the packet header to signal ratchet presence.
        // announceData was built above and ratchetPub is local, so re-derive the flag.
        // We detect ratchet presence by checking whether our ratchet list is active.
        boolean hasRatchet = ratchets != null;
        var contextFlag = hasRatchet ? FLAG_SET : FLAG_UNSET;
        announcePacket.setContextFlag(contextFlag);
        announcePacket.getFlags().setContextType(contextFlag);

        if (send) {
            announcePacket.send();
        } else {
            return announcePacket;
        }

        return null;
    }

    private void incomingLinkRequest(@NonNull final Packet packet) {
        if (acceptLinkRequests) {
            var link = LinkUtils.validateRequest(this, packet.getData(), packet);
            if (nonNull(link)) {
                links.add(link);
            }
        }
    }

    @Override
    public String toString() {
        return "<" + name + "/" + hexHash + ">";
    }
}
