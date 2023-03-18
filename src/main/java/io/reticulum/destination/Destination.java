package io.reticulum.destination;

import io.reticulum.Link;
import io.reticulum.Transport;
import io.reticulum.identity.Identity;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.packet.Packet;
import io.reticulum.packet.PacketContextType;
import io.reticulum.utils.DestinationUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.reticulum.constant.DestinationConstant.PR_TAG_WINDOW;
import static io.reticulum.constant.IdentityConstant.NAME_HASH_LENGTH;
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
    private Map<?, ?> requestHandlers = new HashMap<>();
    private DestinationType type;
    private Direction direction;
    private ProofStrategy proofStrategy = PROVE_NONE;
    private int mtu = 0;
    private Map<Integer, Pair<Long, byte[]>> pathResponses = new HashMap<>();
    private List<Link> links = new ArrayList<>();
    private String name;
    private byte[] nameHash;
    private String hexhash;
    private byte[] defaultAppData;
    private Object callback;
    private Object proofcallback;

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

    @Override
    public String toString() {
        return "<" + name + "/" + hexhash + ">";
    }
}