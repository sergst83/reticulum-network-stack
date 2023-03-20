package io.reticulum.destination;

import io.reticulum.link.Link;
import io.reticulum.Transport;
import io.reticulum.cryptography.Fernet;
import io.reticulum.identity.Identity;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.packet.Packet;
import io.reticulum.packet.PacketContextType;
import io.reticulum.packet.PacketType;
import io.reticulum.utils.DestinationUtils;
import io.reticulum.utils.IdentityUtils;
import io.reticulum.utils.LinkUtils;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.reticulum.constant.DestinationConstant.PR_TAG_WINDOW;
import static io.reticulum.constant.IdentityConstant.NAME_HASH_LENGTH;
import static io.reticulum.destination.DestinationType.GROUP;
import static io.reticulum.destination.DestinationType.PLAIN;
import static io.reticulum.destination.DestinationType.SINGLE;
import static io.reticulum.destination.Direction.IN;
import static io.reticulum.destination.ProofStrategy.PROVE_NONE;
import static io.reticulum.packet.PacketContextType.NONE;
import static io.reticulum.packet.PacketContextType.PATH_RESPONSE;
import static io.reticulum.packet.PacketType.ANNOUNCE;
import static io.reticulum.utils.CommonUtils.longToByteArray;
import static io.reticulum.utils.DestinationUtils.expandName;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static io.reticulum.utils.IdentityUtils.fullHash;
import static io.reticulum.utils.IdentityUtils.getRandomHash;
import static io.reticulum.utils.IdentityUtils.truncatedHash;
import static java.lang.System.currentTimeMillis;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.copyOfRange;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

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
public class Destination {
    private Identity identity;
    private byte[] hash;
    private boolean acceptLinkRequests = true;
    private Callbacks callbacks = new Callbacks();
    private Map<String, RequestHandler> requestHandlers = new ConcurrentHashMap<>();
    private DestinationType type;
    private Direction direction;
    private ProofStrategy proofStrategy = PROVE_NONE;
    private int mtu = 0;
    private Map<Integer, Pair<Long, byte[]>> pathResponses = new ConcurrentHashMap<>();
    private List<Link> links = new CopyOnWriteArrayList<>();
    private String name;
    private byte[] nameHash;
    private String hexhash;
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
            this.identity = new Identity();
            aspectsLocal.add(this.identity.getHexHash());
        }
        if (nonNull(identity) && type == PLAIN) {
            throw new IllegalStateException("Selected destination type PLAIN cannot hold an identity");
        }

        if (nonNull(identity)) {
            this.identity = identity;
        }

        var arrayLocalAspects = aspectsLocal.toArray(String[]::new);
        this.name = expandName(this.identity, appName, arrayLocalAspects);

        // Generate the destination address hash
        this.hash = DestinationUtils.hash(this.identity, appName, arrayLocalAspects);
        this.nameHash = copyOfRange(
                fullHash(expandName(null, appName, arrayLocalAspects).getBytes(UTF_8)),
                0,
                NAME_HASH_LENGTH / 8
        );
        this.hexhash = Hex.encodeHexString(this.hash);
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
    public void setProofRequestedCallback(Consumer<Packet> callback) {
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
            final Function<RequestParams, Object> responseGenerator,
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
                    return identity.decrypt(ciphertext);
                }
                break;
            case GROUP:
                if (nonNull(prv)) {
                    try {
                        return prv.decrypt(ciphertext);
                    } catch (Exception e) {
                        log.error("he GROUP destination could not decrypt data.", e);
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

    public Packet announce() {
        return announce(null, false, null, null, true);
    }

    public Packet announce(boolean pathResponse) {
        return announce(null, pathResponse, null, null, true);
    }

    /**
     * Creates an announce packet for this destination and broadcasts it on all
     * relevant interfaces. Application specific data can be added to the announce.
     *
     * @param appData      byte array containing the app_data
     * @param pathResponse Internal flag used by {@link Transport}. Ignore.
     * @return {@link Packet}
     */
    @SneakyThrows
    public Packet announce(
            final byte[] appData,
            final boolean pathResponse,
            final ConnectionInterface attachedInterface,
            final Integer tag,
            final boolean send
    ) {
        var localAppData = nonNull(appData) ? Arrays.copyOf(appData, appData.length) : null;
        if (this.type == SINGLE) {
            throw new IllegalStateException("Only SINGLE destination types can be announced");
        }

        var staleResponses = new ArrayList<Integer>();
        var now = currentTimeMillis();
        pathResponses.forEach((entryTag, entry) -> {
            if (now > entry.getLeft() + PR_TAG_WINDOW) {
                staleResponses.add(entryTag);
            }
        });

        staleResponses.forEach(pathResponses::remove);

        byte[] announceData;
        if (pathResponse && nonNull(tag) && pathResponses.containsKey(tag)) {
            // This code is currently not used, since Transport will block duplicate
            // path requests based on tags. When multi-path support is implemented in
            // Transport, this will allow Transport to detect redundant paths to the
            // same destination, and select the best one based on chosen criteria,
            // since it will be able to detect that a single emitted announce was
            // received via multiple paths. The difference in reception time will
            // potentially also be useful in determining characteristics of the
            // multiple available paths, and to choose the best one.
            log.trace("Using cached announce data for answering path request with tag {}", tag);
            announceData = pathResponses.get(tag).getRight();
        } else {
            var randomHash = concatArrays(
                    copyOfRange(getRandomHash(), 0, 5),
                    longToByteArray(currentTimeMillis() / 1000, 5)
            );

            if (isNull(localAppData) && nonNull(defaultAppData)) {
                localAppData = defaultAppData;
            }

            var signedData = concatArrays(hash, identity.getPublicKey(), nameHash, randomHash);

            if (nonNull(localAppData)) {
                signedData = concatArrays(signedData, localAppData);
            }

            var signature = identity.sign(signedData);

            announceData = concatArrays(identity.getPublicKey(), nameHash, randomHash, signature);

            if (nonNull(localAppData)) {
                announceData = concatArrays(announceData, localAppData);
            }

            pathResponses.put(tag, Pair.of(currentTimeMillis(), announceData));
        }

        PacketContextType announceContext;
        if (pathResponse) {
            announceContext = PATH_RESPONSE;
        } else {
            announceContext = NONE;
        }

        var announcePacket = new Packet(this, announceData, ANNOUNCE, announceContext, attachedInterface);

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
        return "<" + name + "/" + hexhash + ">";
    }
}
