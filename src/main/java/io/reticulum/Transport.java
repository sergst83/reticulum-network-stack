package io.reticulum;

import io.reticulum.destination.Destination;
import io.reticulum.identity.Identity;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import io.reticulum.packet.PacketReceipt;
import io.reticulum.packet.data.DataPacket;
import io.reticulum.packet.data.DataPacketConverter;
import io.reticulum.transport.AnnounceEntry;
import io.reticulum.transport.AnnounceHandler;
import io.reticulum.transport.AnnounceQueueEntry;
import io.reticulum.transport.Hops;
import io.reticulum.transport.LinkEntry;
import io.reticulum.transport.PathRequestEntry;
import io.reticulum.transport.RateEntry;
import io.reticulum.transport.ReversEntry;
import io.reticulum.transport.Tunnel;
import io.reticulum.utils.IdentityUtils;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static io.reticulum.constant.IdentityConstant.HASHLENGTH;
import static io.reticulum.constant.IdentityConstant.KEYSIZE;
import static io.reticulum.constant.IdentityConstant.NAME_HASH_LENGTH;
import static io.reticulum.constant.IdentityConstant.SIGLENGTH;
import static io.reticulum.constant.LinkConstant.ECPUBSIZE;
import static io.reticulum.constant.LinkConstant.ESTABLISHMENT_TIMEOUT_PER_HOP;
import static io.reticulum.constant.PacketConstant.EXPL_LENGTH;
import static io.reticulum.constant.ReticulumConstant.ANNOUNCE_CAP;
import static io.reticulum.constant.ReticulumConstant.HEADER_MINSIZE;
import static io.reticulum.constant.ReticulumConstant.MAX_QUEUED_ANNOUNCES;
import static io.reticulum.constant.ReticulumConstant.MTU;
import static io.reticulum.constant.ReticulumConstant.TRUNCATED_HASHLENGTH;
import static io.reticulum.constant.TransportConstant.ANNOUNCES_CHECK_INTERVAL;
import static io.reticulum.constant.TransportConstant.APP_NAME;
import static io.reticulum.constant.TransportConstant.AP_PATH_TIME;
import static io.reticulum.constant.TransportConstant.DESTINATION_TIMEOUT;
import static io.reticulum.constant.TransportConstant.DISCOVER_PATHS_FOR;
import static io.reticulum.constant.TransportConstant.JOB_INTERVAL;
import static io.reticulum.constant.TransportConstant.LINKS_CHECK_INTERVAL;
import static io.reticulum.constant.TransportConstant.LINK_TIMEOUT;
import static io.reticulum.constant.TransportConstant.LOCAL_CLIENT_CACHE_MAXSIZE;
import static io.reticulum.constant.TransportConstant.LOCAL_REBROADCASTS_MAX;
import static io.reticulum.constant.TransportConstant.MAX_PR_TAGS;
import static io.reticulum.constant.TransportConstant.MAX_RATE_TIMESTAMPS;
import static io.reticulum.constant.TransportConstant.PATHFINDER_E;
import static io.reticulum.constant.TransportConstant.PATHFINDER_G;
import static io.reticulum.constant.TransportConstant.PATHFINDER_M;
import static io.reticulum.constant.TransportConstant.PATHFINDER_R;
import static io.reticulum.constant.TransportConstant.PATHFINDER_RW;
import static io.reticulum.constant.TransportConstant.PATH_REQUEST_GRACE;
import static io.reticulum.constant.TransportConstant.PATH_REQUEST_MI;
import static io.reticulum.constant.TransportConstant.PATH_REQUEST_TIMEOUT;
import static io.reticulum.constant.TransportConstant.REVERSE_TIMEOUT;
import static io.reticulum.constant.TransportConstant.ROAMING_PATH_TIME;
import static io.reticulum.constant.TransportConstant.TABLES_CULL_INTERVAL;
import static io.reticulum.destination.DestinationType.GROUP;
import static io.reticulum.destination.DestinationType.LINK;
import static io.reticulum.destination.DestinationType.PLAIN;
import static io.reticulum.destination.DestinationType.SINGLE;
import static io.reticulum.destination.Direction.IN;
import static io.reticulum.destination.Direction.OUT;
import static io.reticulum.destination.ProofStrategy.PROVE_ALL;
import static io.reticulum.destination.ProofStrategy.PROVE_APP;
import static io.reticulum.identity.IdentityKnownDestination.recall;
import static io.reticulum.identity.IdentityKnownDestination.recallAppData;
import static io.reticulum.identity.IdentityKnownDestination.validateAnnounce;
import static io.reticulum.interfaces.InterfaceMode.MODE_ACCESS_POINT;
import static io.reticulum.interfaces.InterfaceMode.MODE_BOUNDARY;
import static io.reticulum.interfaces.InterfaceMode.MODE_ROAMING;
import static io.reticulum.link.LinkStatus.ACTIVE;
import static io.reticulum.link.LinkStatus.CLOSED;
import static io.reticulum.packet.HeaderType.HEADER_1;
import static io.reticulum.packet.HeaderType.HEADER_2;
import static io.reticulum.packet.PacketContextType.CACHE_REQUEST;
import static io.reticulum.packet.PacketContextType.CHANNEL;
import static io.reticulum.packet.PacketContextType.KEEPALIVE;
import static io.reticulum.packet.PacketContextType.LRPROOF;
import static io.reticulum.packet.PacketContextType.NONE;
import static io.reticulum.packet.PacketContextType.PATH_RESPONSE;
import static io.reticulum.packet.PacketContextType.RESOURCE;
import static io.reticulum.packet.PacketContextType.RESOURCE_PRF;
import static io.reticulum.packet.PacketContextType.RESOURCE_RCL;
import static io.reticulum.packet.PacketContextType.RESOURCE_REQ;
import static io.reticulum.packet.PacketType.ANNOUNCE;
import static io.reticulum.packet.PacketType.DATA;
import static io.reticulum.packet.PacketType.LINKREQUEST;
import static io.reticulum.packet.PacketType.PROOF;
import static io.reticulum.transport.TransportType.BROADCAST;
import static io.reticulum.transport.TransportType.TRANSPORT;
import static io.reticulum.utils.DestinationUtils.hashFromNameAndIdentity;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static io.reticulum.utils.IdentityUtils.fullHash;
import static io.reticulum.utils.IdentityUtils.getRandomHash;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.ArrayUtils.getLength;
import static org.apache.commons.lang3.ArrayUtils.subarray;
import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.apache.commons.lang3.BooleanUtils.isTrue;
import static org.msgpack.value.ValueFactory.newArray;
import static org.msgpack.value.ValueFactory.newBinary;
import static org.msgpack.value.ValueFactory.newInteger;
import static org.msgpack.value.ValueFactory.newString;
import static org.msgpack.value.ValueFactory.newTimestamp;

@Slf4j
public final class Transport implements ExitHandler {
    private final ReentrantLock savingPacketHashlistLock = new ReentrantLock();
    private final ReentrantLock savingPathTableLock = new ReentrantLock();
    private final ReentrantLock savingTunnelTableLock = new ReentrantLock();
    private final ReentrantLock jobsLock = new ReentrantLock();

    private final AtomicReference<Instant> linksLastChecked = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> announcesLastChecked = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> tablesLastCulled = new AtomicReference<>(Instant.EPOCH);

    private static volatile Transport INSTANCE;
    @Getter
    private final Reticulum owner;
    @Getter
    private Identity identity;

    /**
     * All active interfaces
     */
    @Getter
    private final List<ConnectionInterface> interfaces = new CopyOnWriteArrayList<>();
    /**
     * All active destinations
     */
    @Getter
    private final List<Destination> destinations = new CopyOnWriteArrayList<>();

    /**
     * Interfaces for communicating with local clients connected to a shared Reticulum instance
     */
    @Getter
    private final List<ConnectionInterface> localClientInterfaces = new CopyOnWriteArrayList<>();

    /**
     * A table for storing announces currently waiting to be retransmitted
     */
    private final Map<String, AnnounceEntry> announceTable = new ConcurrentHashMap<>();
    /**
     * A table containing temporarily held announce-table entries
     */
    private final Map<String, AnnounceEntry> heldAnnounces = new ConcurrentHashMap<>();
    /**
     * A table for keeping track of announce rates
     */
    private final Map<String, RateEntry> announceRateTable = new ConcurrentHashMap<>();
    /**
     * A table storing externally registered announce handlers
     */
    private final List<AnnounceHandler> announceHandlers = new CopyOnWriteArrayList<>();
    private final Queue<AnnounceQueueEntry> announceQueue = new ConcurrentLinkedQueue<>();
    /**
     * A table for storing path request timestamps
     */
    private final Map<String, Instant> pathRequests = new ConcurrentHashMap<>();
    /**
     * A lookup table containing the next hop to a given destination
     */
    private final Map<String, Hops> destinationTable = new ConcurrentHashMap<>();
    /**
     * A lookup table for storing packet hashes used to return proofs and replies
     */
    private final Map<String, ReversEntry> reverseTable = new ConcurrentHashMap<>();
    /**
     * A lookup table containing hops for links
     */
    private final Map<String, LinkEntry> linkTable = new ConcurrentHashMap<>();
    /**
     * A table storing tunnels to other transport instances
     */
    private final Map<String, Tunnel> tunnels = new ConcurrentHashMap<>();
    /**
     * Links that are active
     */
    private final List<Link> activeLinks = new CopyOnWriteArrayList<>();
    /**
     * Links that are being established
     */
    private final List<Link> pendingLinks = new CopyOnWriteArrayList<>();
    private final Map<String, ConnectionInterface> pendingLocalPathRequests = new ConcurrentHashMap<>();
    /**
     * A table for keeping track of path requests on behalf of other nodes
     */
    private final Map<String, PathRequestEntry> discoveryPathRequests = new ConcurrentHashMap<>();
    /**
     * A list of packet hashes for duplicate detection
     */
    private final List<byte[]> packetHashList = new CopyOnWriteArrayList<>();
    /**
     * A table for keeping track of tagged path requests
     */
    private final List<byte[]> discoveryPrTags = new CopyOnWriteArrayList<>();
    /**
     * Receipts of all outgoing packets for proof processing
     */
    private final List<PacketReceipt> receipts = new CopyOnWriteArrayList<>();

    //Transport control destinations are used for control purposes like path requests
    private final List<byte[]> controlHashes = new CopyOnWriteArrayList<>();
    private final List<Destination> controlDestinations = new CopyOnWriteArrayList<>();

    private final Deque<Pair<byte[], Integer>> localClientRssiCache = new ConcurrentLinkedDeque<>();
    private final Deque<Pair<byte[], Integer>> localClientSnrCache = new ConcurrentLinkedDeque<>();

    private void init() {
        var transportIdentityPath = owner.getStoragePath().resolve("transport_identity");
        if (Files.isReadable(transportIdentityPath)) {
            identity = Identity.fromFile(transportIdentityPath);
        }

        if (isNull(identity)) {
            log.debug("No valid Transport Identity in storage, creating...");
            identity = new Identity();
            try {
                Files.deleteIfExists(transportIdentityPath);
                Files.write(transportIdentityPath, identity.getPrivateKey(), CREATE, WRITE);
            } catch (IOException e) {
                log.error("Error while saving identity to {}", transportIdentityPath, e);
            }
        } else {
            log.debug("Loaded Transport Identity from storage");
        }

        var packetHashlistPath = owner.getStoragePath().resolve("packet_hashlist");
        if (isFalse(owner.isConnectedToSharedInstance())) {
            if (Files.isReadable(packetHashlistPath)) {
                try (var unpacker = MessagePack.newDefaultUnpacker(Files.readAllBytes(packetHashlistPath))) {
                    packetHashList.clear();
                    packetHashList.addAll(
                            unpacker.unpackValue()
                                    .asArrayValue()
                                    .list()
                                    .stream()
                                    .map(value -> value.asBinaryValue().asByteArray())
                                    .collect(toList())
                    );
                } catch (IOException e) {
                    log.error("Could not load packet hashlist from storage {}", packetHashlistPath, e);
                }
            }
        }

        //Create transport-specific destinations
        var pathRequestDestination = new Destination(null, IN, PLAIN, APP_NAME, "path", "request");
        pathRequestDestination.setPacketCallback(this::pathRequestHandler);
        controlDestinations.add(pathRequestDestination);
        controlHashes.add(pathRequestDestination.getHash());

        var tunnelSynthesizeDestination = new Destination(null, IN, PLAIN, APP_NAME, "tunnel", "synthesize");
        tunnelSynthesizeDestination.setPacketCallback(this::tunnelSynthesizeHandler);
        controlDestinations.add(tunnelSynthesizeDestination);
        controlHashes.add(tunnelSynthesizeDestination.getHash());

        if (owner.isTransportEnabled()) {
            var destinationTablePath = owner.getStoragePath().resolve("destination_table");
            if (Files.isReadable(destinationTablePath) && isFalse(owner.isConnectedToSharedInstance())) {
                try (var unpacker = MessagePack.newDefaultUnpacker(Files.readAllBytes(destinationTablePath))) {
                    for (Value value : unpacker.unpackValue().asArrayValue().list()) {
                        var serialisedEntry = value.asArrayValue();

                        var destinationHash = serialisedEntry.get(0).asBinaryValue().asByteArray();
                        if (getLength(destinationHash) == TRUNCATED_HASHLENGTH / 8) {
                            var timestamp = serialisedEntry.get(1).asTimestampValue().toInstant();
                            var receivedFrom = serialisedEntry.get(2).asBinaryValue().asByteArray();
                            var hops = serialisedEntry.get(3).asIntegerValue().asInt();
                            var expired = serialisedEntry.get(4).asTimestampValue().toInstant();
                            var randomBlods = serialisedEntry.get(5).asArrayValue().list().stream()
                                    .map(v -> v.asBinaryValue().asByteArray())
                                    .collect(toList());
                            var receivingInterface = findInterfaceFromHash(serialisedEntry.get(6).asBinaryValue().asByteArray());
                            var announcePacket = getCachedPacket(serialisedEntry.get(7).asBinaryValue().asByteArray());

                            if (nonNull(announcePacket) && nonNull(receivingInterface)) {
                                announcePacket.unpack();
                                // We increase the hops, since reading a packet
                                // from cache is equivalent to receiving it again
                                // over an interface. It is cached with it's non-
                                // increased hop-count.
                                announcePacket.setHops(announcePacket.getHops() + 1);
                                destinationTable.put(
                                        Hex.encodeHexString(destinationHash),
                                        Hops.builder()
                                                .timestamp(timestamp)
                                                .via(receivedFrom)
                                                .expires(expired)
                                                .hops(hops)
                                                .randomBlobs(randomBlods)
                                                .packet(announcePacket)
                                                .build()
                                );
                                log.debug("Loaded path table entry for {} from storage {}", Hex.encodeHexString(destinationHash), destinationTablePath);
                            } else {
                                log.debug("Could not reconstruct path table entry from storage for {}", Hex.encodeHexString(destinationHash));
                                if (isNull(announcePacket)) {
                                    log.debug("The announce packet could not be loaded from cache");
                                }
                                if (isNull(receivingInterface)) {
                                    log.debug("The interface is no longer available");
                                }
                            }
                        }
                    }

                    log.debug("Loaded {}  path table entries  from storage", destinationTable.size());
                } catch (IOException e) {
                    log.error("Could not load destination table from storage {}", destinationTablePath, e);
                }
            }

            var tunnelTablePath = owner.getStoragePath().resolve("tunnels");
            if (Files.isReadable(tunnelTablePath) && isFalse(owner.isConnectedToSharedInstance())) {
                try (var unpacker = MessagePack.newDefaultUnpacker(Files.readAllBytes(tunnelTablePath))) {
                    for (Value value : unpacker.unpackValue().asArrayValue()) {
                        var serialisedTunnel = value.asArrayValue();

                        var tunnelId = serialisedTunnel.get(0).asBinaryValue().asByteArray();
                        var interfaceHash = serialisedTunnel.get(1).asBinaryValue().asByteArray();
                        var serialisedPaths = serialisedTunnel.get(2).asArrayValue();
                        var expires = serialisedTunnel.get(3).asTimestampValue().toInstant();

                        var tunnelPaths = new HashMap<String, Hops>();
                        for (Value serialisedPathValue : serialisedPaths) {
                            var serialisedEntry = serialisedPathValue.asArrayValue();

                            var destinationHash = serialisedEntry.get(0).asBinaryValue().asByteArray();
                            var timestamp = serialisedEntry.get(1).asTimestampValue().toInstant();
                            var receivedFrom = serialisedEntry.get(2).asBinaryValue().asByteArray();
                            var hops = serialisedEntry.get(3).asIntegerValue().asInt();
                            var expired = serialisedEntry.get(4).asTimestampValue().toInstant();
                            var randomBlods = serialisedEntry.get(5).asArrayValue().list().stream()
                                    .map(v -> v.asBinaryValue().asByteArray())
                                    .collect(toList());
                            var receivingInterface = findInterfaceFromHash(serialisedEntry.get(6).asBinaryValue().asByteArray());
                            var announcePacket = getCachedPacket(serialisedEntry.get(7).asBinaryValue().asByteArray());

                            if (nonNull(announcePacket)) {
                                // We increase the hops, since reading a packet
                                // from cache is equivalent to receiving it again
                                // over an interface. It is cached with it's non-
                                // increased hop-count.
                                announcePacket.setHops(announcePacket.getHops() + 1);

                                var tunnelPath = Hops.builder()
                                        .timestamp(timestamp)
                                        .via(receivedFrom)
                                        .expires(expired)
                                        .randomBlobs(randomBlods)
                                        .anInterface(receivingInterface)
                                        .packet(announcePacket)
                                        .build();
                                tunnelPaths.put(Hex.encodeHexString(destinationHash), tunnelPath);
                            }
                        }

                        tunnels.put(
                                Hex.encodeHexString(tunnelId),
                                Tunnel.builder()
                                        .tunnelId(tunnelId)
                                        .tunnelPaths(tunnelPaths)
                                        .expires(expires)
                                        .build()
                        );
                    }

                    log.debug("Loaded {} tunnel table entries from storage", tunnels.size());
                } catch (IOException e) {
                    log.error("Could not load tunnel table from storage {}", tunnelTablePath, e);
                }
            }
        }

        //Synthesize tunnels for any interfaces wanting it
        for (ConnectionInterface anInterface : interfaces) {
            anInterface.setTunnelId(null);
            if (anInterface.wantsTunnel()) {
                synthesizeTunnel(anInterface);
            }
        }
    }

    private Transport(@NonNull Reticulum reticulum) {
        this.owner = reticulum;
    }

    public static Transport start(@NonNull Reticulum reticulum) {
        Transport transport = INSTANCE;
        if (transport == null) {
            synchronized (Transport.class) {
                transport = INSTANCE;
                if (transport == null) {
                    INSTANCE = transport = new Transport(reticulum);
                    transport.init();
                }
            }
        }

        Executors
                .newSingleThreadScheduledExecutor()
                .scheduleWithFixedDelay(transport::jobs, 10, JOB_INTERVAL, TimeUnit.MILLISECONDS);

        log.info("Transport instance {} started", transport.identity.getHexHash());

        return transport;
    }

    public static Transport getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("You have to call start method first to init transport instance");
        }

        return INSTANCE;
    }

    @Override
    public void exitHandler() {
        if (owner.isConnectedToSharedInstance()) {
            persistData();
        }
    }

    public void detachInterfaces() {
        var detachableInterfaces = new LinkedList<ConnectionInterface>();

        for (ConnectionInterface anInterface : interfaces) {
            // Currently no rules are being applied
            // here, and all interfaces will be sent
            // the detach call on RNS teardown.
            if (true) {
                detachableInterfaces.add(anInterface);
            } else {
                //pass
            }
        }

        for (ConnectionInterface localClientInterface : localClientInterfaces) {
            // Currently no rules are being applied
            // here, and all interfaces will be sent
            // the detach call on RNS teardown.
            if (true) {
                detachableInterfaces.add(localClientInterface);
            } else {
                //pass
            }
        }

        detachableInterfaces.forEach(ConnectionInterface::detach);
    }

    public void persistData() {
        savePacketHashlist();
        savePathTable();
        saveTunnelTable();
    }

    private void savePacketHashlist() {
        if (owner.isConnectedToSharedInstance()) {
            return;
        }

        try {
            if (savingPacketHashlistLock.tryLock(5, TimeUnit.SECONDS)) {
                var saveStart = Instant.now();

                if (isFalse(owner.isTransportEnabled())) {
                    packetHashList.clear();
                } else {
                    log.debug("Saving packet hashlist to storage...");
                }

                var packetHashlistPath = owner.getStoragePath().resolve("packet_hashlist");
                try (var packer = MessagePack.newDefaultBufferPacker()) {
                    packer.packValue(
                            newArray(
                                    packetHashList.stream()
                                            .map(ValueFactory::newBinary)
                                            .collect(toList())
                            )
                    );

                    Files.deleteIfExists(packetHashlistPath);
                    Files.write(packetHashlistPath, packer.toByteArray(), CREATE, WRITE);
                } catch (IOException e) {
                    log.error("Could not save packet hashlist to storage", e);
                }

                log.debug("Saved packet hashlist in {} ms", Duration.between(saveStart, Instant.now()).toMillis());
            } else {
                log.error("Could not save packet hashlist to storage, waiting for previous save operation timed out.");
            }
        } catch (Exception e) {
            log.error("Error", e);
        } finally {
            savingPacketHashlistLock.unlock();
        }
    }

    private void savePathTable() {
        if (owner.isConnectedToSharedInstance()) {
            return;
        }

        try {
            if (savingPathTableLock.tryLock(5, TimeUnit.SECONDS)) {
                var saveStart = Instant.now();

                log.debug("Saving path table to storage...");

                var serialisedDestinations = new LinkedList<Value>();
                for (String destinationHash : destinationTable.keySet()) {
                    // Get the destination entry from the destination table
                    var de = destinationTable.get(destinationHash);
                    var interfaceHash = de.getInterface().getHash();

                    //Only store destination table entry if the associated interface is still active
                    var iface = findInterfaceFromHash(interfaceHash);
                    if (nonNull(iface)) {
                        //Get the destination entry from the destination table
                        serialisedDestinations.add(
                                newArray(
                                        newBinary(Hex.decodeHex(destinationHash)),
                                        newTimestamp(de.getTimestamp()),
                                        newBinary(de.getVia()),
                                        newInteger(de.getHops()),
                                        newTimestamp(de.getExpires()),
                                        newArray(de.getRandomBlobs().stream().map(ValueFactory::newBinary).collect(toList())),
                                        newBinary(interfaceHash),
                                        newBinary(de.getPacket().getHash())
                                )
                        );
                        cache(de.getPacket(), true);
                    }
                }

                try (var packer = MessagePack.newDefaultBufferPacker()) {
                    packer.packValue(newArray(serialisedDestinations));

                    var destinationTablePath = owner.getStoragePath().resolve("destination_table");
                    Files.deleteIfExists(destinationTablePath);
                    Files.write(destinationTablePath, packer.toByteArray(), CREATE, WRITE);
                }

                log.debug("Saved {}  path table entries in {} ms", serialisedDestinations, Duration.between(saveStart, Instant.now()).toMillis());
            } else {
                log.error("Could not save path table to storage, waiting for previous save operation timed out.");
            }
        } catch (Exception e) {
            log.error("Error", e);
        } finally {
            savingPathTableLock.unlock();
        }
    }

    /**
     * When caching packets to storage, they are written
     * exactly as they arrived over their interface. This
     * means that they have not had their hop count
     * increased yet! Take note of this when reading from
     * the packet cache.
     *
     * @param packet
     * @param forceCache
     */
    private void cache(Packet packet, boolean forceCache) {
        if (forceCache || shouldCache(packet)) {
            try {
                var stringHash = Hex.encodeHexString(packet.getHash());
                String interfaceName = null;
                if (nonNull(packet.getReceivingInterface())) {
                    interfaceName = packet.getReceivingInterface().getInterfaceName();
                }

                try (var packer = MessagePack.newDefaultBufferPacker()) {
                    packer.packValue(
                            newArray(
                                    newBinary(packet.getRaw()),
                                    newString(interfaceName)
                            )
                    );

                    var filePath = owner.getCachePath().resolve(stringHash);
                    Files.deleteIfExists(filePath);
                    Files.write(filePath, packer.toByteArray(), CREATE, WRITE);
                }
            } catch (Exception e) {
                log.error("Error writing packet to cache", e);
            }
        }
    }

    private Packet getCachedPacket(byte[] packetHash) {
        var strPacketHash = Hex.encodeHexString(packetHash);
        var path = owner.getCachePath().resolve(strPacketHash);

        if (Files.isReadable(path)) {
            try (var unpacker = MessagePack.newDefaultUnpacker(Files.readAllBytes(path))) {
                var arrayValue = unpacker.unpackValue().asArrayValue();
                var raw = arrayValue.get(0).asBinaryValue().asByteArray();
                var interfaceName = arrayValue.get(1).asStringValue().asString();

                var packet = new Packet(raw);
                var iface = interfaces.stream()
                        .filter(i -> StringUtils.equals(i.getInterfaceName(), interfaceName))
                        .findAny()
                        .orElse(null);
                packet.setReceivingInterface(iface);

                return packet;
            } catch (IOException e) {
                log.error("Exception occurred while getting cached packet.", e);
            }
        }

        return null;
    }

    private boolean shouldCache(Packet packet) {
        return packet.getContext() == RESOURCE_PRF;
    }

    private ConnectionInterface findInterfaceFromHash(byte[] interfaceHash) {
        return interfaces.stream()
                .filter(iface -> Arrays.equals(iface.getHash(), interfaceHash))
                .findFirst()
                .orElse(null);
    }

    private void saveTunnelTable() {
        if (owner.isConnectedToSharedInstance()) {
            return;
        }

        try {
            if (savingTunnelTableLock.tryLock(5, TimeUnit.SECONDS)) {
                var start = Instant.now();

                log.debug("Saving tunnel table to storage...");

                var serialisedTunnels = new LinkedList<Value>();
                for (String tunnelId : tunnels.keySet()) {
                    var te = tunnels.get(tunnelId);
                    var iface = te.getInterface();
                    var tunnelPaths = te.getTunnelPaths();
                    var expires = te.getExpires();

                    byte[] interfaceHash = null;
                    if (nonNull(iface)) {
                        interfaceHash = iface.getHash();
                    }

                    var serialisedPaths = new LinkedList<Value>();
                    for (String destinationHash : tunnelPaths.keySet()) {
                        var de = tunnelPaths.get(destinationHash);

                        serialisedPaths.add(
                                newArray(
                                        newBinary(Hex.decodeHex(destinationHash)),
                                        newTimestamp(de.getTimestamp()),
                                        newBinary(de.getVia()),
                                        newInteger(de.getHops()),
                                        newTimestamp(de.getExpires()),
                                        newArray(de.getRandomBlobs().stream().map(ValueFactory::newBinary).collect(toList())),
                                        newBinary(interfaceHash),
                                        newBinary(de.getPacket().getHash())
                                )
                        );

                        cache(de.getPacket(), true);
                    }

                    serialisedTunnels.add(
                            newArray(
                                    newBinary(Hex.decodeHex(tunnelId)),
                                    newBinary(interfaceHash),
                                    newArray(serialisedPaths),
                                    newTimestamp(expires)
                            )
                    );
                }

                try (var packer = MessagePack.newDefaultBufferPacker()) {
                    packer.packValue(newArray(serialisedTunnels));

                    var filePath = owner.getStoragePath().resolve("tunnels");
                    Files.deleteIfExists(filePath);
                    Files.write(filePath, packer.toByteArray(), CREATE, WRITE);
                }

                log.debug("Saved {} tunnel table entries in {} ms", serialisedTunnels.size(), Duration.between(start, Instant.now()).toMillis());
            } else {
                log.error("Could not save tunnel table to storage, waiting for previous save operation timed out.");
            }
        } catch (Exception e) {
            log.error("Error", e);
        } finally {
            savingTunnelTableLock.unlock();
        }
    }

    // TODO: 12.05.2023 подлежит рефакторингу.
    public void inbound(final byte[] raw, final ConnectionInterface iface) {
        byte[] localRaw;
        //If interface access codes are enabled, we must authenticate each packet.
        if (getLength(raw) > 2) {
            if (nonNull(iface) && nonNull(iface.getIdentity())) {
                //Check that IFAC flag is set
                if ((raw[0] & 0x80) == 0x80) {
                    if (getLength(raw) > 2 + iface.getIfacSize()) {
                        //Extract IFAC
                        var ifac = subarray(raw, 2, 2 + iface.getIfacSize());

                        //Generate mask
                        var hkdf = new HKDFBytesGenerator(new SHA256Digest());
                        hkdf.init(new HKDFParameters(ifac, iface.getIfacKey(), new byte[0]));
                        var mask = new byte[getLength(raw)];
                        hkdf.generateBytes(mask, 0, mask.length);

                        //Unmask payload
                        var i = 0;
                        var unmaskedRaw = new byte[0];
                        for (byte b : raw) {
                            if (i <= 1 || i > iface.getIfacSize() + 1) {
                                //Unmask header bytes and payload
                                unmaskedRaw = ArrayUtils.add(unmaskedRaw, (byte) (b ^ mask[i]));
                            } else {
                                //Don't unmask IFAC itself
                                unmaskedRaw = ArrayUtils.add(unmaskedRaw, b);
                            }
                            i++;
                        }

                        //Unset IFAC flag
                        var newHeader = new byte[]{(byte) (unmaskedRaw[0] & 0x7f), raw[1]};

                        //Re-assemble packet
                        var newRaw = concatArrays(newHeader, subarray(unmaskedRaw, 2 + iface.getIfacSize(), unmaskedRaw.length));

                        //Calculate expected IFAC
                        var signed = iface.getIdentity().sign(newRaw);
                        var expectedIfac = subarray(signed, signed.length - iface.getIfacSize(), signed.length);

                        //Check it
                        if (Arrays.equals(ifac, expectedIfac)) {
                            localRaw = newRaw;
                        } else {
                            return;
                        }
                    } else {
                        return;
                    }
                } else {
                    //If the IFAC flag is not set, but should be, drop the packet.
                    return;
                }
            } else {
                //If the interface does not have IFAC enabled, check the received packet IFAC flag.
                if ((raw[0] & 0x80) == 0x80) {
                    //If the flag is set, drop the packet
                    return;
                }

                localRaw = raw;
            }
        } else {
            return;
        }

        while (isFalse(jobsLock.tryLock())) {
            //sleep
            log.debug("jobs locked by {}", jobsLock);
        }

        if (isNull(identity)) {
            return;
        }

        var packet = new Packet(localRaw);
        if (isFalse(packet.unpack())) {
            return;
        }

        packet.setReceivingInterface(iface);
        packet.setHops(packet.getHops() + 1);

        if (nonNull(iface)) {
            if (nonNull(iface.getRStatRssi())) {
                packet.setRssi(iface.getRStatRssi());
                if (CollectionUtils.isNotEmpty(localClientInterfaces)) {
                    localClientRssiCache.add(Pair.of(packet.getHash(), packet.getRssi()));

                    while (localClientRssiCache.size() > LOCAL_CLIENT_CACHE_MAXSIZE) {
                        localClientRssiCache.pop();
                    }
                }
            }

            if (nonNull(iface.getRStatSnr())) {
                packet.setSnr(iface.getRStatSnr());
                if (CollectionUtils.isNotEmpty(localClientInterfaces)) {
                    localClientSnrCache.add(Pair.of(packet.getHash(), packet.getSnr()));

                    while (localClientSnrCache.size() > LOCAL_CLIENT_CACHE_MAXSIZE) {
                        localClientSnrCache.pop();
                    }
                }
            }
        }

        if (CollectionUtils.isNotEmpty(localClientInterfaces)) {
            if (isLocalClientInterface(iface)) {
                packet.setHops(packet.getHops() - 1);
            }
        } else if (interfaceToSharedInstance(iface)) {
            packet.setHops(packet.getHops() - 1);
        }

        if (packetFilter(packet)) {
            packetHashList.add(packet.getHash());
            cache(packet, false);

            //Check special conditions for local clients connected through a shared Reticulum instance
            var fromLocalClient = localClientInterfaces.contains(packet.getReceivingInterface());
            var forLocalClient = packet.getPacketType() != ANNOUNCE
                    && destinationTable.containsKey(Hex.encodeHexString(packet.getDestinationHash()))
                    && destinationTable.get(Hex.encodeHexString(packet.getDestinationHash())).getHops() == 0;
            var forLocalClientLink = packet.getPacketType() != ANNOUNCE
                    && linkTable.containsKey(Hex.encodeHexString(packet.getDestinationHash()))
                    && (
                    localClientInterfaces.contains(linkTable.get(Hex.encodeHexString(packet.getDestinationHash())).getNextHopInterface())
                            || localClientInterfaces.contains(linkTable.get(Hex.encodeHexString(packet.getDestinationHash())).getReceivingInterface())
            );
            var proofForLocalClient = reverseTable.containsKey(Hex.encodeHexString(packet.getDestinationHash()))
                    && localClientInterfaces.contains(reverseTable.get(Hex.encodeHexString(packet.getDestinationHash())).getReceivingInterface());

            //Plain broadcast packets from local clients are sent
            // directly on all attached interfaces, since they are
            // never injected into transport.
            if (controlHashes.stream().noneMatch(hash -> Arrays.equals(packet.getDestinationHash(), hash))) {
                if (packet.getDestinationType() == PLAIN && packet.getTransportType() == BROADCAST) {
                    //Send to all interfaces except the originator
                    if (fromLocalClient) {
                        for (ConnectionInterface anInterface : interfaces) {
                            if (isFalse(anInterface.equals(packet.getReceivingInterface()))) {
                                transmit(anInterface, packet.getRaw());
                            }
                        }
                    } else {
                        //If the packet was not from a local client, send it directly to all local clients
                        for (ConnectionInterface localClientInterface : localClientInterfaces) {
                            transmit(localClientInterface, packet.getRaw());
                        }
                    }
                }
            }

            //General transport handling.
            // Takes care of directing packets according to transport tables and recording entries in reverse and link tables.
            if (owner.isTransportEnabled() || fromLocalClient || forLocalClient || forLocalClientLink) {
                // If there is no transport id, but the packet is for a local client, we generate the transport
                // id (it was stripped on the previous hop, since we "spoof" the hop count for clients behind a
                // shared instance, so they look directly reach-able), and reinsert, so the normal transport
                // implementation can handle the packet.
                if (isNull(packet.getTransportId()) && forLocalClient) {
                    packet.setTransportId(identity.getHash());
                }

                // If this is a cache request, and we can fullfill it, do so and stop processing. Otherwise resume normal processing.
                if (packet.getContext() == CACHE_REQUEST) {
                    if (cacheRequestPacket(packet)) {
                        return;
                    }
                }

                // If the packet is in transport, check whether we are the designated next hop, and process it accordingly if we are.
                if (nonNull(packet.getTransportId()) && packet.getPacketType() != ANNOUNCE) {
                    if (Arrays.equals(packet.getTransportId(), identity.getHash())) {
                        if (destinationTable.containsKey(Hex.encodeHexString(packet.getDestinationHash()))) {
                            var hopsEntry = destinationTable.get(Hex.encodeHexString(packet.getDestinationHash()));
                            var nextHop = hopsEntry.getVia();
                            var remainingHops = hopsEntry.getHops();

                            DataPacket dataPacket = new DataPacket();
                            if (remainingHops > 1) {
                                //Just increase hop count and transmit
                                dataPacket = DataPacketConverter.fromBytes(packet.getRaw());
                                dataPacket.getAddresses().setHash1(nextHop);
                            } else if (remainingHops == 1) {
                                //Strip transport headers and transmit
                                dataPacket = DataPacketConverter.fromBytes(packet.getRaw());
                                dataPacket.getHeader().getFlags().setHeaderType(HEADER_1);
                                dataPacket.getHeader().getFlags().setPropagationType(BROADCAST);
                                dataPacket.getHeader().setHops((byte) packet.getHops());
                            } else if (remainingHops == 0) {
                                //Just increase hop count and transmit
                                dataPacket = DataPacketConverter.fromBytes(packet.getRaw());
                                dataPacket.getHeader().setHops((byte) packet.getHops());
                            }

                            var outboundInterface = destinationTable.get(Hex.encodeHexString(packet.getDestinationHash())).getInterface();

                            if (packet.getPacketType() == LINKREQUEST) {
                                var now = Instant.now();
                                var proofTimeout = now.plusMillis((long) ESTABLISHMENT_TIMEOUT_PER_HOP * Math.max(1, remainingHops));

                                //Entry format is
                                var linkEntry = LinkEntry.builder()
                                        .timestamp(now)
                                        .nextHop(nextHop)
                                        .nextHopInterface(outboundInterface)
                                        .remainingHops(remainingHops)
                                        .receivingInterface(packet.getReceivingInterface())
                                        .hops(packet.getHops())
                                        .destinationHash(packet.getDestinationHash())
                                        .validated(false)
                                        .proofTimestamp(proofTimeout)
                                        .build();

                                linkTable.put(Hex.encodeHexString(packet.getTruncatedHash()), linkEntry);
                            } else {
                                //Entry format is
                                var reserveEntry = ReversEntry.builder()
                                        .receivingInterface(packet.getReceivingInterface())
                                        .outboundInterface(outboundInterface)
                                        .timestamp(Instant.now())
                                        .build();

                                reverseTable.put(Hex.encodeHexString(packet.getDestinationHash()), reserveEntry);
                            }

                            transmit(outboundInterface, DataPacketConverter.toBytes(dataPacket));
                            hopsEntry.setTimestamp(Instant.now());
                        } else {
                            // TODO: 11.05.2023 There should probably be some kind of REJECT
                            // mechanism here, to signal to the source that their expected path failed.
                            log.debug(
                                    "Got packet in transport, but no known path to final destination {}. Dropping packet.",
                                    Hex.encodeHexString(packet.getDestinationHash())
                            );
                        }
                    }
                }

                //Link transport handling. Directs packets according to entries in the link tables
                if (packet.getPacketType() != ANNOUNCE && packet.getPacketType() != LINKREQUEST && packet.getContext() != LRPROOF) {
                    if (linkTable.containsKey(Hex.encodeHexString(packet.getDestinationHash()))) {
                        var linkEntry = linkTable.get(Hex.encodeHexString(packet.getDestinationHash()));
                        //If receiving and outbound interface is the same for this link, direction doesn't matter, and we simply send the packet on.
                        ConnectionInterface outboundInterface = null;
                        if (Objects.equals(linkEntry.getNextHopInterface(), linkEntry.getReceivingInterface())) {
                            //But check that taken hops matches one of the expectede values.
                            if (packet.getHops() == linkEntry.getHops() || packet.getHops() == linkEntry.getRemainingHops()) {
                                outboundInterface = linkEntry.getNextHopInterface();
                            }
                        } else {
                            // If interfaces differ, we transmit on the opposite interface of what the packet was received on.
                            if (Objects.equals(packet.getReceivingInterface(), linkEntry.getNextHopInterface())) {
                                //Also check that expected hop count matches
                                if (packet.getHops() == linkEntry.getRemainingHops()) {
                                    outboundInterface = linkEntry.getReceivingInterface();
                                }
                            } else if (Objects.equals(packet.getReceivingInterface(), linkEntry.getReceivingInterface())) {
                                //Also check that expected hop count matches
                                if (packet.getHops() == linkEntry.getHops()) {
                                    outboundInterface = linkEntry.getNextHopInterface();
                                }
                            }
                        }

                        if (nonNull(outboundInterface)) {
                            var dataPacket = DataPacketConverter.fromBytes(packet.getRaw());
                            dataPacket.getHeader().setHops((byte) packet.getHops());
                            transmit(outboundInterface, DataPacketConverter.toBytes(dataPacket));
                            linkEntry.setTimestamp(Instant.now());
                        }
                    }
                }
            }

            //Announce handling.
            // Handles logic related to incoming announces, queueing rebroadcasts of these, and removal
            // of queued announce rebroadcasts once handed to the next node.
            if (packet.getPacketType() == ANNOUNCE) {
                var localDestination = destinations.stream()
                        .filter(destination -> Arrays.equals(destination.getHash(), packet.getDestinationHash()))
                        .findFirst()
                        .orElse(null);
                if (isNull(localDestination) && validateAnnounce(packet)) {
                    byte[] receivedFrom;
                    if (nonNull(packet.getTransportId())) {
                        receivedFrom = packet.getTransportId();

                        //Check if this is a next retransmission from another node. If it is, we're removing the
                        // announce in question from our pending table
                        if (owner.isTransportEnabled() && announceTable.containsKey(Hex.encodeHexString(packet.getDestinationHash()))) {
                            var announceEntry = announceTable.get(Hex.encodeHexString(packet.getDestinationHash()));

                            if (packet.getHops() - 1 == announceEntry.getHops()) {
                                log.debug("Heard a local rebroadcast of announce for {}", Hex.encodeHexString(packet.getDestinationHash()));
                                announceEntry.setLocalRebroadcasts(announceEntry.getLocalRebroadcasts() + 1);
                                if (announceEntry.getLocalRebroadcasts() >= LOCAL_REBROADCASTS_MAX) {
                                    log.debug("Max local rebroadcasts of announce for {} reached, dropping announce from our table",
                                            Hex.encodeHexString(packet.getDestinationHash()));
                                    announceTable.remove(Hex.encodeHexString(packet.getDestinationHash()));
                                }
                            }

                            if (packet.getHops() - 1 == announceEntry.getHops() + 1 && announceEntry.getRetries() > 0) {
                                var now = Instant.now();
                                if (now.isBefore(announceEntry.getRetransmitTimeout())) {
                                    log.debug("Rebroadcasted announce for {} has been passed on to another node, no further tries needed",
                                            Hex.encodeHexString(packet.getDestinationHash()));
                                    announceTable.remove(Hex.encodeHexString(packet.getDestinationHash()));
                                }
                            }
                        }
                    } else {
                        receivedFrom = packet.getDestinationHash();
                    }

                    //Check if this announce should be inserted into announce and destination tables
                    var shouldAdd = false;

                    //First, check that the announce is not for a destination local to this system, and that hops are less than the max
                    if (
                            destinations.stream().noneMatch(destination -> Arrays.equals(destination.getHash(), packet.getDestinationHash()))
                                    && packet.getHops() < PATHFINDER_M + 1
                    ) {
                        var announceEmitted = announceEmitted(packet);

                        var randomBlob = subarray(
                                packet.getData(),
                                KEYSIZE / 8 + NAME_HASH_LENGTH / 8,
                                KEYSIZE / 8 + NAME_HASH_LENGTH / 8 + 10
                        );
                        List<byte[]> randomBlobs = new ArrayList<>();
                        if (destinationTable.containsKey(Hex.encodeHexString(packet.getDestinationHash()))) {
                            var hopsEntry = destinationTable.get(Hex.encodeHexString(packet.getDestinationHash()));
                            randomBlobs = hopsEntry.getRandomBlobs();

                            //If we already have a path to the announced destination, but the hop count is equal or less, we'll update our tables.
                            if (packet.getHops() < hopsEntry.getHops()) {
                                //Make sure we haven't heard the random blob before, so announces can't be replayed to forge paths.
                                // TODO: 11.05.2023 Check whether this approach works under all circumstances
                                if (randomBlobs.stream().noneMatch(bytes -> Arrays.equals(bytes, randomBlob))) {
                                    shouldAdd = true;
                                } else {
                                    shouldAdd = false;
                                }
                            } else {
                                //If an announce arrives with a larger hop count than we already have in the table,
                                // ignore it, unless the path is expired, or the emission timestamp is more recent.
                                var now = Instant.now();
                                var pathExpires = hopsEntry.getExpires();

                                var pathAnnounceEmitted = 0L;
                                for (byte[] pathRandomBlob : randomBlobs) {
                                    pathAnnounceEmitted = Math.max(
                                            pathAnnounceEmitted,
                                            new BigInteger(subarray(pathRandomBlob, 5, 10)).longValue()
                                    );
                                    if (pathAnnounceEmitted > announceEmitted) {
                                        break;
                                    }
                                }

                                if (now.isAfter(pathExpires)) {
                                    //We also check that the announce is different from ones we've already heard, to avoid loops in the network
                                    if (randomBlobs.stream().noneMatch(bytes -> Arrays.equals(bytes, randomBlob))) {
                                        // TODO: 11.05.2023 Check that this ^ approach actually works under all circumstances
                                        log.debug("Replacing destination table entry for {} with new announce due to expired path",
                                                Hex.encodeHexString(packet.getDestinationHash()));
                                        shouldAdd = true;
                                    } else {
                                        shouldAdd = false;
                                    }
                                } else {
                                    if (announceEmitted > pathAnnounceEmitted) {
                                        if (randomBlobs.stream().noneMatch(bytes -> Arrays.equals(bytes, randomBlob))) {
                                            log.debug("Replacing destination table entry for {}  with new announce, since it was more recently emitted",
                                                    Hex.encodeHexString(packet.getDestinationHash()));
                                            shouldAdd = true;
                                        } else {
                                            shouldAdd = false;
                                        }
                                    }
                                }
                            }
                        } else {
                            //If this destination is unknown in our table we should add it
                            shouldAdd = true;
                        }

                        if (shouldAdd) {
                            var now = Instant.now();

                            var rateBlocked = false;
                            if (packet.getContext() != PATH_RESPONSE && nonNull(packet.getReceivingInterface().getAnnounceRateTarget())) {
                                if (isFalse(announceRateTable.containsKey(Hex.encodeHexString(packet.getDestinationHash())))) {
                                    var rateEntryBuilder = RateEntry.builder()
                                            .last(now)
                                            .rateViolations(0)
                                            .blockedUntil(Instant.EPOCH)
                                            .timestamps(new ArrayList<>() {{
                                                add(now);
                                            }});
                                    announceRateTable.put(Hex.encodeHexString(packet.getDestinationHash()), rateEntryBuilder.build());
                                } else {
                                    var rateEntry = announceRateTable.get(Hex.encodeHexString(packet.getDestinationHash()));
                                    rateEntry.getTimestamps().add(now);

                                    while (rateEntry.getTimestamps().size() > MAX_RATE_TIMESTAMPS) {
                                        rateEntry.getTimestamps().remove(0);
                                    }

                                    var currentRate = rateEntry.getLast();
                                    if (now.isAfter(rateEntry.getBlockedUntil())) {
                                        if (Duration.between(currentRate, now).toSeconds() < packet.getReceivingInterface().getAnnounceRateTarget()) {
                                            rateEntry.setRateViolations(rateEntry.getRateViolations() + 1);
                                        } else {
                                            rateEntry.setRateViolations(Math.max(0, rateEntry.getRateViolations() - 1));
                                        }

                                        if (rateEntry.getRateViolations() > packet.getReceivingInterface().getAnnounceRateGrace()) {
                                            var rateTarget = packet.getReceivingInterface().getAnnounceRateTarget();
                                            var ratePenalty = packet.getReceivingInterface().getAnnounceRatePenalty();
                                            rateEntry.setBlockedUntil(rateEntry.getLast().plusSeconds(rateTarget).plusSeconds(ratePenalty));
                                            rateBlocked = true;
                                        } else {
                                            rateEntry.setLast(now);
                                        }
                                    } else {
                                        rateBlocked = true;
                                    }
                                }
                            }

                            var retries = 0;
                            var announceHops = packet.getHops();
                            var localRebroadcasts = 0;
                            var blockRebroadcasts = false;
                            var attachedInterface = (ConnectionInterface) null;
                            var expires = (Instant) null;

                            var retransmitTimeout = now.plusMillis((long) (Math.random() * PATHFINDER_RW));

                            if (nonNull(packet.getReceivingInterface().getMode()) && packet.getReceivingInterface().getMode() == MODE_ACCESS_POINT) {
                                expires = now.plusSeconds(AP_PATH_TIME);
                            } else if (nonNull(packet.getReceivingInterface().getMode()) && packet.getReceivingInterface().getMode() == MODE_ROAMING) {
                                expires = now.plusSeconds(ROAMING_PATH_TIME);
                            } else {
                                expires = now.plusSeconds(PATHFINDER_E);
                            }

                            randomBlobs.add(randomBlob);

                            if ((owner.isTransportEnabled() || fromLocalClient(packet)) && packet.getContext() != PATH_RESPONSE) {
                                //Insert announce into announce table for retransmission

                                if (rateBlocked) {
                                    log.debug("Blocking rebroadcast of announce from {} due to excessive announce rate",
                                            Hex.encodeHexString(packet.getDestinationHash()));
                                } else {
                                    if (fromLocalClient(packet)) {
                                        //If the announce is from a local client, it is announced immediately, but only one time.
                                        retransmitTimeout = now;
                                        retries = PATHFINDER_R;
                                    }

                                    announceTable.put(
                                            Hex.encodeHexString(packet.getDestinationHash()),
                                            AnnounceEntry.builder()
                                                    .timestamp(now)
                                                    .retransmitTimeout(retransmitTimeout)
                                                    .retries(retries)
                                                    .transportId(receivedFrom)
                                                    .packet(packet)
                                                    .localRebroadcasts(localRebroadcasts)
                                                    .blockRebroadcasts(blockRebroadcasts)
                                                    .attachedInterface(attachedInterface)
                                                    .build()
                                    );
                                }
                            }
                            // TODO: 11.05.2023 Check from_local_client once and store result
                            else if (fromLocalClient(packet) && packet.getContext() == PATH_RESPONSE) {
                                //If this is a path response from a local client, check if any external interfaces have pending path requests.
                                if (pendingLocalPathRequests.containsKey(Hex.encodeHexString(packet.getDestinationHash()))) {
                                    pendingLocalPathRequests.remove(Hex.encodeHexString(packet.getDestinationHash()));
                                    retransmitTimeout = now;
                                    retries = PATHFINDER_R;

                                    announceTable.put(
                                            Hex.encodeHexString(packet.getDestinationHash()),
                                            AnnounceEntry.builder()
                                                    .timestamp(now)
                                                    .retransmitTimeout(retransmitTimeout)
                                                    .retries(retries)
                                                    .transportId(receivedFrom)
                                                    .hops(announceHops)
                                                    .packet(packet)
                                                    .localRebroadcasts(localRebroadcasts)
                                                    .blockRebroadcasts(blockRebroadcasts)
                                                    .attachedInterface(attachedInterface)
                                                    .build()
                                    );
                                }
                            }

                            //If we have any local clients connected, we re-transmit the announce to them immediately
                            if (CollectionUtils.isNotEmpty(localClientInterfaces)) {
                                var announceIdentity = recall(packet.getDestinationHash());
                                var announceDestination = new Destination(announceIdentity, OUT, SINGLE, "unknown", "unknown");
                                announceDestination.setHash(packet.getDestinationHash());
                                announceDestination.setHexHash(Hex.encodeHexString(announceDestination.getHash()));
                                var announceContext = NONE;
                                var announceData = packet.getData();

                                // TODO: 11.05.2023 Shouldn't the context be PATH_RESPONSE in the first case here?
                                if (fromLocalClient(packet) && packet.getContext() == PATH_RESPONSE) {
                                    for (ConnectionInterface localClientInterface : localClientInterfaces) {
                                        if (isFalse(localClientInterface.equals(packet.getReceivingInterface()))) {
                                            var newAnnounce = new Packet(
                                                    announceDestination,
                                                    announceData,
                                                    ANNOUNCE,
                                                    announceContext,
                                                    HEADER_2,
                                                    TRANSPORT,
                                                    identity.getHash(),
                                                    localClientInterface
                                            );
                                            newAnnounce.setHops(packet.getHops());
                                            newAnnounce.send();
                                        }
                                    }
                                } else {
                                    for (ConnectionInterface localClientInterface : localClientInterfaces) {
                                        if (isFalse(localClientInterface.equals(packet.getReceivingInterface()))) {
                                            var newAnnounce = new Packet(
                                                    announceDestination,
                                                    announceData,
                                                    ANNOUNCE,
                                                    announceContext,
                                                    HEADER_2,
                                                    TRANSPORT,
                                                    identity.getHash(),
                                                    localClientInterface
                                            );
                                            newAnnounce.setHops(packet.getHops());
                                            newAnnounce.send();
                                        }
                                    }
                                }
                            }

                            //If we have any waiting discovery path requests for this destination, we retransmit to that
                            // interface immediately
                            if (discoveryPathRequests.containsKey(Hex.encodeHexString(packet.getDestinationHash()))) {
                                var prEntry = discoveryPathRequests.get(Hex.encodeHexString(packet.getDestinationHash()));
                                attachedInterface = prEntry.getRequestingInterface();

                                log.debug("Got matching announce, answering waiting discovery path request for {} on {}",
                                        Hex.encodeHexString(packet.getDestinationHash()), attachedInterface.getInterfaceName()
                                );
                                var announceIdentity = recall(packet.getDestinationHash());
                                var announceDestination = new Destination(announceIdentity, OUT, SINGLE, "unknown", "unknown");
                                announceDestination.setHash(packet.getDestinationHash());
                                announceDestination.setHexHash(Hex.encodeHexString(announceDestination.getHash()));
                                var announceData = packet.getData();
                                var newAnnounce = new Packet(
                                        announceDestination,
                                        announceData,
                                        ANNOUNCE,
                                        PATH_RESPONSE,
                                        HEADER_2,
                                        TRANSPORT,
                                        identity.getHash(),
                                        attachedInterface
                                );
                                newAnnounce.setHops(packet.getHops());
                                newAnnounce.send();
                            }

                            var destinationTableEntry = Hops.builder()
                                    .timestamp(now)
                                    .via(receivedFrom)
                                    .hops(announceHops)
                                    .expires(expires)
                                    .randomBlobs(randomBlobs)
                                    .anInterface(packet.getReceivingInterface())
                                    .packet(packet)
                                    .build();
                            destinationTable.put(
                                    Hex.encodeHexString(packet.getDestinationHash()),
                                    destinationTableEntry
                            );
                            log.debug(
                                    "Destination {} is now {} hops away via {} on {}",
                                    Hex.encodeHexString(packet.getDestinationHash()),
                                    announceHops,
                                    Hex.encodeHexString(receivedFrom),
                                    packet.getReceivingInterface().getInterfaceName()
                            );

                            //If the receiving interface is a tunnel, we add the announce to the tunnels table
                            if (
                                    nonNull(packet.getReceivingInterface().getTunnelId())
                                            && tunnels.containsKey(Hex.encodeHexString(packet.getReceivingInterface().getTunnelId()))
                            ) {
                                var tunnelEntry = tunnels.get(Hex.encodeHexString(packet.getReceivingInterface().getTunnelId()));
                                var paths = tunnelEntry.getTunnelPaths();
                                paths.put(Hex.encodeHexString(packet.getDestinationHash()), destinationTableEntry);
                                expires = Instant.now().plusSeconds(DESTINATION_TIMEOUT);
                                tunnelEntry.setExpires(expires);
                                log.debug(
                                        "Path to {} associated with tunnel {}.",
                                        Hex.encodeHexString(packet.getDestinationHash()),
                                        Hex.encodeHexString(packet.getReceivingInterface().getTunnelId())
                                );
                            }

                            //Call externally registered callbacks from apps wanting to know when an announce arrives
                            if (packet.getContext() != PATH_RESPONSE) {
                                for (AnnounceHandler handler : announceHandlers) {
                                    try {
                                        //Check that the announced destination matches the handlers aspect filter
                                        var executeCallback = false;
                                        var announceIdentity = recall(packet.getDestinationHash());
                                        if (isNull(handler.getAspectFilter())) {
                                            //If the handlers aspect filter is set to None, we execute the callback in all cases
                                            executeCallback = true;
                                        } else {
                                            var handlerExpectedHash = hashFromNameAndIdentity(handler.getAspectFilter(), announceIdentity);
                                            if (Arrays.equals(packet.getDestinationHash(), handlerExpectedHash)) {
                                                executeCallback = true;
                                            }
                                        }

                                        if (executeCallback) {
                                            handler.receivedAnnounce(
                                                    packet.getDestinationHash(),
                                                    announceIdentity,
                                                    recallAppData(packet.getDestinationHash())
                                            );
                                        }
                                    } catch (Exception e) {
                                        log.error("Error while processing external announce callback.", e);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            //Handling for linkrequests to local destinations
            else if (packet.getPacketType() == LINKREQUEST) {
                if (isNull(packet.getTransportId()) || Arrays.equals(packet.getTransportId(), identity.getHash())) {
                    for (Destination destination : destinations) {
                        if (
                                Arrays.equals(destination.getHash(), packet.getDestinationHash())
                                        && destination.getType() == packet.getDestinationType()
                        ) {
                            packet.setDestination(destination);
                            destination.receive(packet);
                        }
                    }
                }
            }

            //Handling for local data packets
            else if (packet.getPacketType() == DATA) {
                if (packet.getDestinationType() == LINK) {
                    for (Link link : activeLinks) {
                        if (Arrays.equals(link.getLinkId(), packet.getDestinationHash())) {
                            packet.setDestination(link);
                            link.receive(packet);
                        }
                    }
                } else {
                    for (Destination destination : destinations) {
                        if (
                                Arrays.equals(destination.getHash(), packet.getDestinationHash())
                                        && destination.getType() == packet.getDestinationType()
                        ) {
                            packet.setDestination(destination);
                            destination.receive(packet);

                            if (destination.getProofStrategy() == PROVE_ALL) {
                                packet.prove(null);
                            } else if (destination.getProofStrategy() == PROVE_APP) {
                                if (nonNull(destination.getCallbacks().getProofRequested())) {
                                    try {
                                        if (destination.getCallbacks().getProofRequested().apply(packet)) {
                                            packet.prove(null);
                                        }
                                    } catch (Exception e) {
                                        log.error("Error while executing proof request callback.", e);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            //Handling for proofs and link-request proofs
            else if (packet.getPacketType() == PROOF) {
                if (packet.getContext() == LRPROOF) {
                    // This is a link request proof, check if it needs to be transported
                    if (
                            (owner.isTransportEnabled() || forLocalClient || forLocalClientLink)
                                    && linkTable.containsKey(Hex.encodeHexString(packet.getDestinationHash()))
                    ) {
                        var linkEntry = linkTable.get(Hex.encodeHexString(packet.getDestinationHash()));
                        if (Objects.equals(packet.getReceivingInterface(), linkEntry.getNextHopInterface())) {
                            try {
                                if (getLength(packet.getData()) == (SIGLENGTH / 8 + ECPUBSIZE / 2)) {
                                    var peerPubBytes = subarray(
                                            packet.getData(),
                                            SIGLENGTH / 8,
                                            SIGLENGTH / 8 + ECPUBSIZE / 2
                                    );
                                    var peerIdentity = recall(linkEntry.getDestinationHash());
                                    var peerSigPubBytes = subarray(peerIdentity.getPublicKey(), ECPUBSIZE / 2, ECPUBSIZE);

                                    var signedData = concatArrays(packet.getDestinationHash(), peerPubBytes, peerSigPubBytes);
                                    var signature = subarray(packet.getData(), 0, SIGLENGTH / 8);

                                    if (peerIdentity.validate(signature, signedData)) {
                                        log.debug("Link request proof validated for transport via {}", linkEntry.getReceivingInterface().getInterfaceName());
                                        var dataPacket = DataPacketConverter.fromBytes(packet.getRaw());
                                        dataPacket.getHeader().setHops((byte) packet.getHops());
                                        linkEntry.setValidated(true);
                                        transmit(linkEntry.getReceivingInterface(), DataPacketConverter.toBytes(dataPacket));
                                    } else {
                                        log.debug("Invalid link request proof in transport for link {}, dropping proof.",
                                                Hex.encodeHexString(packet.getDestinationHash()));
                                    }
                                }
                            } catch (Exception e) {
                                log.error("Error while transporting link request proof.", e);
                            }
                        } else {
                            log.debug("Link request proof received on wrong interface, not transporting it.");
                        }
                    } else {
                        //Check if we can deliver it to a local pending link
                        for (Link link : pendingLinks) {
                            if (Arrays.equals(link.getLinkId(), packet.getDestinationHash())) {
                                link.validateProof(packet);
                            }
                        }
                    }
                } else if (packet.getContext() == RESOURCE_PRF) {
                    for (Link link : activeLinks) {
                        if (Arrays.equals(link.getLinkId(), packet.getDestinationHash())) {
                            link.receive(packet);
                        }
                    }
                } else {
                    if (packet.getDestinationType() == LINK) {
                        for (Link link : activeLinks) {
                            if (Arrays.equals(link.getLinkId(), packet.getDestinationHash())) {
                                packet.setDestination(link);
                            }
                        }
                    }

                    var proofHash = getLength(packet.getData()) == EXPL_LENGTH
                            ? subarray(packet.getData(), 0, HASHLENGTH / 8)
                            : null;

                    //Check if this proof neds to be transported
                    if (
                            (owner.isTransportEnabled() || fromLocalClient || proofForLocalClient)
                                    && reverseTable.containsKey(Hex.encodeHexString(packet.getDestinationHash()))
                    ) {
                        var reverseEntry = reverseTable.remove(Hex.encodeHexString(packet.getDestinationHash()));
                        if (Objects.equals(packet.getReceivingInterface(), reverseEntry.getOutboundInterface())) {
                            log.debug("Proof received on correct interface, transporting it via {}",
                                    reverseEntry.getReceivingInterface().getInterfaceName());
                            var dataPacket = DataPacketConverter.fromBytes(packet.getRaw());
                            dataPacket.getHeader().setHops((byte) packet.getHops());
                            transmit(reverseEntry.getOutboundInterface(), DataPacketConverter.toBytes(dataPacket));
                        } else {
                            log.debug("Proof received on wrong interface, not transporting it.");
                        }
                    }

                    for (PacketReceipt receipt : receipts) {
                        var receiptValidated = false;
                        if (nonNull(proofHash)) {
                            //Only test validation if hash matches
                            if (Arrays.equals(receipt.getHash(), proofHash)) {
                                receiptValidated = receipt.validateProofPacket(packet);
                            }
                        } else {
                            // TODO: 12.05.2023 This looks like it should actually be rewritten when implicit proofs are added.

                            //In case of an implicit proof, we have to check every single outstanding receipt
                            receiptValidated = receipt.validateProofPacket(packet);
                        }

                        if (receiptValidated) {
                            receipts.remove(receipt);
                        }
                    }
                }
            }
        }

        jobsLock.unlock();
    }

    // TODO: 12.05.2023 подлежит рефакторингу.
    public boolean outbound(@NonNull final Packet packet) {
        while (isFalse(jobsLock.tryLock())) {
            //sleep
        }

        var sent = false;
        var outboundTime = Instant.now();

        //Check if we have a known path for the destination in the path table
        if (
                packet.getPacketType() != ANNOUNCE
                        && packet.getDestination().getType() != PLAIN
                        && packet.getDestination().getType() != GROUP
                        && destinationTable.containsKey(Hex.encodeHexString(packet.getDestinationHash()))
        ) {
            var hopsEntry = destinationTable.get(Hex.encodeHexString(packet.getDestinationHash()));
            var outboundInterface = hopsEntry.getInterface();

            //If there's more than one hop to the destination, and we know
            // a path, we insert the packet into transport by adding the next
            // transport nodes address to the header, and modifying the flags.
            // This rule applies both for "normal" transport, and when connected
            // to a local shared Reticulum instance.
            if (hopsEntry.getHops() > 1) {
                if (packet.getHeaderType() == HEADER_1) {
                    //Insert packet into transport
                    var dataPacket = DataPacketConverter.fromBytes(packet.getRaw());
                    dataPacket.getHeader().getFlags().setHeaderType(HEADER_2);
                    dataPacket.getHeader().getFlags().setPropagationType(TRANSPORT);
                    dataPacket.getAddresses().setHash1(dataPacket.getAddresses().getHash1());
                    dataPacket.getAddresses().setHash2(hopsEntry.getVia());

                    transmit(outboundInterface, DataPacketConverter.toBytes(dataPacket));
                    hopsEntry.setTimestamp(outboundTime);
                    sent = true;
                }
            }

            // In the special case where we are connected to a local shared
            // Reticulum instance, and the destination is one hop away, we
            // also add transport headers to inject the packet into transport
            // via the shared instance. Normally a packet for a destination
            // one hop away would just be broadcast directly, but since we
            // are "behind" a shared instance, we need to get that instance
            // to transport it onto the network.
            else if (hopsEntry.getHops() == 1 && owner.isConnectedToSharedInstance()) {
                if (packet.getHeaderType() == HEADER_1) {
                    //Insert packet into transport
                    var dataPacket = DataPacketConverter.fromBytes(packet.getRaw());
                    dataPacket.getHeader().getFlags().setHeaderType(HEADER_2);
                    dataPacket.getHeader().getFlags().setPropagationType(TRANSPORT);
                    dataPacket.getAddresses().setHash1(dataPacket.getAddresses().getHash1());
                    dataPacket.getAddresses().setHash2(hopsEntry.getVia());

                    transmit(outboundInterface, DataPacketConverter.toBytes(dataPacket));
                    hopsEntry.setTimestamp(outboundTime);
                    sent = true;
                }
            }

            // If none of the above applies, we know the destination is
            // directly reachable, and also on which interface, so we
            // simply transmit the packet directly on that one.
            else {
                transmit(outboundInterface, packet.getRaw());
                sent = true;
            }
        }

        // If we don't have a known path for the destination, we'll
        // broadcast the packet on all outgoing interfaces, or
        // just the relevant interface if the packet has an attached
        // interface, or belongs to a link.
        else {
            var storedHash = false;
            for (ConnectionInterface anInterface : interfaces) {
                if (anInterface.OUT()) {
                    var shouldTransmit = true;

                    if (packet.getDestination().getType() == LINK) {
                        if (((Link) packet.getDestination()).getStatus() == CLOSED) {
                            shouldTransmit = false;
                        }
                        if (isFalse(Objects.equals(anInterface, ((Link) packet.getDestination()).getAttachedInterface()))) {
                            shouldTransmit = false;
                        }
                    }

                    if (nonNull(packet.getAttachedInterface())
                            && isFalse(Objects.equals(anInterface, packet.getAttachedInterface()))) {
                        shouldTransmit = false;
                    }

                    if (packet.getPacketType() == ANNOUNCE) {
                        if (isNull(packet.getAttachedInterface())) {
                            if (anInterface.getMode() == MODE_ACCESS_POINT) {
                                log.debug("Blocking announce broadcast on {} due to AP mode", anInterface.getInterfaceName());
                                shouldTransmit = false;
                            } else if (anInterface.getMode() == MODE_ROAMING) {
                                var localDestination = destinations.stream()
                                        .filter(destination -> Arrays.equals(destination.getHash(), packet.getDestinationHash()))
                                        .findFirst()
                                        .orElse(null);
                                if (nonNull(localDestination)) {
                                    //log.debug("Allowing announce broadcast on roaming-mode interface from instance-local destination")
                                    //pass
                                } else {
                                    var fromInterface = nextHopInterface(packet.getDestinationHash());
                                    if (isNull(fromInterface) || isNull(fromInterface.getMode())) {
                                        shouldTransmit = false;
                                        if (isNull(fromInterface)) {
                                            log.debug("Blocking announce broadcast on {} since next hop interface doesn't exist",
                                                    anInterface.getInterfaceName());
                                        } else if (isNull(fromInterface.getMode())) {
                                            log.debug("Blocking announce broadcast on {} since next hop interface has no mode configured",
                                                    anInterface.getInterfaceName());
                                        }
                                    } else {
                                        if (fromInterface.getMode() == MODE_ROAMING) {
                                            log.debug("Blocking announce broadcast on {} due to roaming-mode next-hop interface",
                                                    anInterface.getInterfaceName());
                                            shouldTransmit = false;
                                        } else if (fromInterface.getMode() == MODE_BOUNDARY) {
                                            log.debug("Blocking announce broadcast on {}  due to boundary-mode next-hop interfacee",
                                                    anInterface.getInterfaceName());
                                            shouldTransmit = false;
                                        }
                                    }
                                }
                            } else if (anInterface.getMode() == MODE_BOUNDARY) {
                                var localDestination = destinations.stream()
                                        .filter(destination -> Arrays.equals(destination.getHash(), packet.getDestinationHash()))
                                        .findFirst()
                                        .orElse(null);
                                if (nonNull(localDestination)) {
                                    //log.debug("Allowing announce broadcast on boundary-mode interface from instance-local destination")
                                    //pass
                                } else {
                                    var fromInterface = nextHopInterface(packet.getDestinationHash());
                                    if (isNull(fromInterface) || isNull(fromInterface.getMode())) {
                                        shouldTransmit = false;
                                        if (isNull(fromInterface)) {
                                            log.debug("Blocking announce broadcast on {} since next hop interface doesn't exist",
                                                    anInterface.getInterfaceName());
                                        } else if (isNull(fromInterface.getMode())) {
                                            log.debug("Blocking announce broadcast on {} since next hop interface has no mode configured",
                                                    anInterface.getInterfaceName());
                                        }
                                    } else {
                                        if (fromInterface.getMode() == MODE_ROAMING) {
                                            log.debug("Blocking announce broadcast on {} due to roaming-mode next-hop interface",
                                                    anInterface.getInterfaceName());
                                            shouldTransmit = false;
                                        }
                                    }
                                }
                            } else {
                                // Currently, annouces originating locally are always
                                // allowed, and do not conform to bandwidth caps.
                                // TODO: Rethink whether this is actually optimal.
                                if (packet.getHops() > 0) {
                                    if (isNull(anInterface.getAnnounceCap())) {
                                        anInterface.setAnnounceCap(ANNOUNCE_CAP);
                                    }
                                    if (isNull(anInterface.getAnnounceAllowedAt())) {
                                        anInterface.setAnnounceAllowedAt(Instant.EPOCH);
                                    }

                                    var queuedAnnounces = CollectionUtils.isNotEmpty(anInterface.getAnnounceQueue());
                                    if (isFalse(queuedAnnounces) && outboundTime.isAfter(anInterface.getAnnounceAllowedAt())) {
                                        var txTime = packet.getRaw().length * 8 / anInterface.getBitrate();
                                        var waitTime = txTime / anInterface.getAnnounceCap();
                                        anInterface.setAnnounceAllowedAt(outboundTime.plusSeconds((long) waitTime));
                                    } else {
                                        shouldTransmit = false;
                                        if (isFalse(anInterface.getAnnounceQueue().size() >= MAX_QUEUED_ANNOUNCES)) {
                                            var shouldQueue = true;

                                            var alreadyQueued = false;
                                            AnnounceQueueEntry existingEntry = null;
                                            for (AnnounceQueueEntry e : announceQueue) {
                                                if (Arrays.equals(e.getDestination(), packet.getDestinationHash())) {
                                                    alreadyQueued = true;
                                                    existingEntry = e;
                                                }
                                            }

                                            var emissionTimestamp = announceEmitted(packet);
                                            if (alreadyQueued) {
                                                shouldQueue = false;

                                                if (emissionTimestamp > existingEntry.getEmitted()) {
                                                    existingEntry.setTime(outboundTime);
                                                    existingEntry.setHops(packet.getHops());
                                                    existingEntry.setEmitted(emissionTimestamp);
                                                    existingEntry.setRaw(packet.getRaw());
                                                }
                                            }

                                            if (shouldQueue) {
                                                var entry = AnnounceQueueEntry.builder()
                                                        .destination(packet.getDestinationHash())
                                                        .time(outboundTime)
                                                        .hops(packet.getHops())
                                                        .emitted(emissionTimestamp)
                                                        .raw(packet.getRaw())
                                                        .build();

                                                queuedAnnounces = CollectionUtils.isNotEmpty(anInterface.getAnnounceQueue());
                                                anInterface.getAnnounceQueue().add(entry);

                                                var waitTime = Math.max(Duration.between(Instant.now(), anInterface.getAnnounceAllowedAt()).toMillis(), 0);
                                                if (isFalse(queuedAnnounces)) {
                                                    Executors.defaultThreadFactory().newThread(anInterface::processAnnounceQueue).start();
                                                }
                                                log.debug(
                                                        "Added announce to queue (height {}) on {} for processing in {} ms",
                                                        CollectionUtils.size(anInterface.getAnnounceQueue()),
                                                        anInterface.getInterfaceName(),
                                                        waitTime
                                                );
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (shouldTransmit) {
                        if (isFalse(storedHash)) {
                            packetHashList.add(packet.getPacketHash());
                            storedHash = true;
                        }

                        // TODO: Re-evaluate potential for blocking
                        // def send_packet():
                        //     Transport.transmit(interface, packet.raw)
                        // thread = threading.Thread(target=send_packet)
                        // thread.daemon = True
                        // thread.start()

                        transmit(anInterface, packet.getRaw());
                        sent = true;
                    }
                }
            }
        }

        if (sent) {
            packet.setSent(true);
            packet.setSentAt(Instant.now());

            //Don't generate receipt if it has been explicitly disabled
            if (
                    isTrue(packet.isCreateReceipt())
                            && packet.getPacketType() == DATA //Only generate receipts for DATA packets
                            && packet.getDestination().getType() == PLAIN //Don't generate receipts for PLAIN destinations
                            && isFalse(packet.getContext().getValue() >= KEEPALIVE.getValue() && packet.getContext().getValue() <= LRPROOF.getValue()) //Don't generate receipts for link-related packets
                            && isFalse(packet.getContext().getValue() >= RESOURCE.getValue() && packet.getContext().getValue() <= RESOURCE_RCL.getValue()) //Don't generate receipts for resource packets
            ) {
                packet.setReceipt(new PacketReceipt(packet));
                receipts.add(packet.getReceipt());
            }

            cache(packet, false);
        }

        jobsLock.unlock();

        return sent;
    }

    /**
     * @param destinationHash
     * @return The interface for the next hop to the specified destination, or null if the interface is unknown.
     */
    private ConnectionInterface nextHopInterface(byte[] destinationHash) {
        return Optional.ofNullable(destinationTable.get(Hex.encodeHexString(destinationHash)))
                .map(Hops::getInterface)
                .orElse(null);
    }

    private boolean fromLocalClient(Packet packet) {
        if (nonNull(packet.getReceivingInterface().getParentInterface())) {
            return isLocalClientInterface(packet.getReceivingInterface().getParentInterface());
        }

        return false;
    }

    private boolean cacheRequestPacket(Packet packet) {
        if (getLength(packet.getData()) == HASHLENGTH / 8) {
            var localPacket = getCachedPacket(packet.getData());
            if (nonNull(localPacket)) {
                //If the packet was retrieved from the local cache, replay it to the Transport instance,
                // so that it can be directed towards it original destination.
                inbound(localPacket.getRaw(), localPacket.getReceivingInterface());

                return true;
            } else {
                return false;
            }
        }

        return false;
    }

    private boolean packetFilter(Packet packet) {
        // TODO: 12.05.2023 Think long and hard about this.
        //Is it even strictly necessary with the current transport rules?
        if (Arrays.asList(KEEPALIVE, RESOURCE_REQ, RESOURCE_PRF, RESOURCE, CACHE_REQUEST, CHANNEL).contains(packet.getContext())) {
            return true;
        }

        if (packet.getDestinationType() == PLAIN) {
            if (packet.getPacketType() != ANNOUNCE) {
                if (packet.getHops() > 1) {
                    log.debug("Dropped PLAIN packet {} with {} hops", Hex.encodeHexString(packet.getHash()), packet.getHops());
                    return false;
                } else {
                    return true;
                }
            } else {
                log.debug("Dropped invalid PLAIN announce packet");
                return false;
            }
        }

        if (packet.getDestinationType() == GROUP) {
            if (packet.getPacketType() != ANNOUNCE) {
                if (packet.getHops() > 1) {
                    log.debug("Dropped PLAIN packet {} with {} hops", Hex.encodeHexString(packet.getHash()), packet.getHops());
                    return false;
                } else {
                    return true;
                }
            } else {
                log.debug("Dropped invalid GROUP announce packet");
                return false;
            }
        }

        if (packetHashList.stream().noneMatch(bytes -> Arrays.equals(bytes, packet.getPacketHash()))) {
            return true;
        } else {
            if (packet.getPacketType() == ANNOUNCE) {
                if (packet.getDestinationType() == SINGLE) {
                    return true;
                } else {
                    log.debug("Dropped invalid announce packet");
                    return false;
                }
            }
        }

        log.trace("Filtered packet with hash {}", Hex.encodeHexString(packet.getPacketHash()));

        return false;
    }

    private boolean interfaceToSharedInstance(ConnectionInterface iface) {
        return nonNull(iface) && iface.isConnectedToSharedInstance();
    }

    private boolean isLocalClientInterface(ConnectionInterface iface) {
        return nonNull(iface) && nonNull(iface.getParentInterface()) && iface.getParentInterface().isLocalSharedInstance();
    }

    public void sharedConnectionDisappeared() {
        for (Link activeLink : activeLinks) {
            activeLink.teardown();
        }

        for (Link pendingLink : pendingLinks) {
            pendingLink.teardown();
        }

        announceTable.clear();
        destinationTable.clear();
        reverseTable.clear();
        linkTable.clear();
        heldAnnounces.clear();
        announceHandlers.clear();
        tunnels.clear();
    }

    public void sharedConnectionReappeared() {
        if (owner.isConnectedToSharedInstance()) {
            for (Destination registeredDestination : destinations) {
                if (registeredDestination.getType() == SINGLE) {
                    registeredDestination.announce(true);
                }
            }
        }
    }

    public void dropAnnounceQueues() {
        for (ConnectionInterface anInterface : interfaces) {
            if (CollectionUtils.isNotEmpty(anInterface.getAnnounceQueue())) {
                var na = anInterface.getAnnounceQueue().size();
                if (na > 0) {
                    var naStr = String.format("%s announce", na);
                    try {
                        anInterface.getAnnounceQueue().clear();
                    } catch (Exception e) {
                        //ignore
                    }
                    log.debug("Dropped {} on {}", naStr, interfaces);
                }
            }
        }
    }

    public long announceEmitted(Packet packet) {
        var randomBlob = ArrayUtils.subarray(
                packet.getData(),
                KEYSIZE / 8 + NAME_HASH_LENGTH / 8,
                KEYSIZE / 8 + NAME_HASH_LENGTH / 8 + 10
        );

        return new BigInteger(ArrayUtils.subarray(randomBlob, 5, 10)).longValue();
    }

    public void registerDestination(Destination destination) {
        destination.setMtu(MTU);
        if (destination.getDirection() == IN) {
            for (Destination registeredDestination : destinations) {
                if (Arrays.equals(destination.getHash(), registeredDestination.getHash())) {
                    throw new IllegalStateException("Attempt to register an already registered destination.");
                }
            }

            destinations.add(destination);

            if (owner.isConnectedToSharedInstance()) {
                if (destination.getType() == SINGLE) {
                    destination.announce(true);
                }
            }
        }
    }

    /**
     * @param destinationHash
     * @return The number of hops to the specified destination, or ``RNS.Transport.PATHFINDER_M`` if the number of hops is unknown.
     */
    public int hopsTo(byte[] destinationHash) {
        return Optional.ofNullable(destinationTable.get(Hex.encodeHexString(destinationHash)))
                .map(Hops::getPathLength)
                .orElse(PATHFINDER_M);
    }

    public void registerLink(@NonNull Link link) {
        log.trace("Registering link {}", link);
        if (link.isInitiator()) {
            pendingLinks.add(link);
        } else {
            activeLinks.add(link);
        }
    }

    public void activateLink(@NonNull Link link) {
        log.trace("Activating link {}", link);
        if (pendingLinks.contains(link)) {
            pendingLinks.remove(link);
            activeLinks.add(link);
            link.setStatus(ACTIVE);
        } else {
            log.error("Attempted to activate a link that was not in the pending table");
        }
    }

    public void cacheRequest(byte[] packetHash, Link destination) {
        var cachedPacket = getCachedPacket(packetHash);
        if (nonNull(cachedPacket)) {
            //The packet was found in the local cache, replay it to the Transport instance.
            inbound(cachedPacket.getRaw(), cachedPacket.getReceivingInterface());
        } else {
            //The packet is not in the local cache, query the network.
            new Packet(destination, packetHash, CACHE_REQUEST).send();
        }
    }

    public void registerAnnounceHandler(AnnounceHandler announceHandler) {
        announceHandlers.add(announceHandler);
    }

    public void deregisterAnnounceHandler(AnnounceHandler announceHandler) {
        announceHandlers.remove(announceHandler);
    }

    private void transmit(ConnectionInterface iface, byte[] raw) {
        try {
            if (nonNull(iface.getIdentity())) {
                //Calculate packet access code
                var signed = iface.getIdentity().sign(raw);
                var ifac = subarray(signed, signed.length - iface.getIfacSize(), signed.length);

                //Generate mask
                var hkdf = new HKDFBytesGenerator(new SHA256Digest());
                hkdf.init(new HKDFParameters(ifac, iface.getIfacKey(), new byte[0]));
                var mask = new byte[getLength(raw) + iface.getIfacSize()];
                hkdf.generateBytes(mask, 0, mask.length);

                //Set IFAC flag
                var dataPacket = DataPacketConverter.fromBytes(raw);
                dataPacket.getHeader().getFlags().setAccessCodes(true);
                dataPacket.setIfac(ifac);

                var newRaw = DataPacketConverter.toBytes(dataPacket);
                var maskedRaw = new byte[0];
                for (int i = 0; i < newRaw.length; i++) {
                    if (i == 0) {
                        //Mask first header byte, but make sure the IFAC flag is still set
                        maskedRaw = ArrayUtils.add(maskedRaw, (byte) (newRaw[i] ^ mask[i] | 0x80));
                    } else if (i == 1 || i > iface.getIfacSize() + 1) {
                        //Mask second header byte and payload
                        maskedRaw = ArrayUtils.add(maskedRaw, (byte) (newRaw[i] ^ mask[i]));
                    } else {
                        //Don't mask the IFAC itself
                        maskedRaw = ArrayUtils.add(maskedRaw, newRaw[i]);
                    }
                }

                //Send it
                iface.processOutgoing(maskedRaw);
            } else {
                iface.processOutgoing(raw);
            }
        } catch (Exception e) {
            log.error("Error while transmitting on {}.", iface.getInterfaceName(), e);
        }
    }

    private void pathRequestHandler(byte[] data, Packet packet) {
        try {
            // If there is at least bytes enough for a destination
            // hash in the packet, we assume those bytes are the
            // destination being requested.
            if (getLength(data) >= TRUNCATED_HASHLENGTH / 8) {
                var destinationHash = subarray(data, 0, TRUNCATED_HASHLENGTH / 8);
                // If there is also enough bytes for a transport
                // instance ID and at least one tag byte, we
                // assume the next bytes to be the trasport ID
                // of the requesting transport instance.
                var requestTransportInstance = getLength(data) > (TRUNCATED_HASHLENGTH / 8 * 2)
                        ? subarray(data, TRUNCATED_HASHLENGTH / 8, TRUNCATED_HASHLENGTH / 8 * 2)
                        : null;
                byte[] tagBytes = null;
                if (getLength(data) > (TRUNCATED_HASHLENGTH / 8 * 2)) {
                    tagBytes = subarray(data, TRUNCATED_HASHLENGTH / 8 * 2, data.length);
                } else if (getLength(data) > (TRUNCATED_HASHLENGTH / 8)) {
                    tagBytes = subarray(data, TRUNCATED_HASHLENGTH / 8, data.length);
                }

                if (nonNull(tagBytes)) {
                    if (tagBytes.length > (TRUNCATED_HASHLENGTH / 8)) {
                        tagBytes = subarray(tagBytes, 0, TRUNCATED_HASHLENGTH / 8);
                    }

                    var uniqueTag = concatArrays(destinationHash, tagBytes);
                    if (discoveryPrTags.stream().noneMatch(bytes -> Arrays.equals(bytes, uniqueTag))) {
                        discoveryPrTags.add(uniqueTag);

                        pathRequest(
                                destinationHash,
                                fromLocalClient(packet),
                                packet.getReceivingInterface(),
                                requestTransportInstance,
                                tagBytes
                        );
                    } else {
                        log.debug("Ignoring duplicate path request for {} with tag {}",
                                Hex.encodeHexString(destinationHash), Hex.encodeHexString(uniqueTag));
                    }
                } else {
                    log.debug("Ignoring tagless path request for {}.", Hex.encodeHexString(destinationHash));
                }
            }
        } catch (Exception e) {
            log.error("Error while handling path request.", e);
        }
    }

    private void pathRequest(
            byte[] destinationHash,
            boolean isFromLocalClient,
            ConnectionInterface attachedInterface,
            byte[] requestorTransportId,
            byte[] tag
    ) {
        var shouldSearchForUnknown = false;

        if (nonNull(attachedInterface)) {
            if (owner.isTransportEnabled() && DISCOVER_PATHS_FOR.contains(attachedInterface.getMode())) {
                shouldSearchForUnknown = true;
            }
        }

        log.debug("Path request for {} on {}", Hex.encodeHexString(destinationHash), attachedInterface);

        if (localClientInterfaces.size() > 0) {
            if (destinationTable.containsKey(Hex.encodeHexString(destinationHash))) {
                var destinationInterface = destinationTable.get(Hex.encodeHexString(destinationHash)).getInterface();

                if (isLocalClientInterface(destinationInterface)) {
                    pendingLocalPathRequests.put(
                            Hex.encodeHexString(destinationHash),
                            attachedInterface
                    );
                }
            }
        }

        var localDestination = destinations.stream()
                .filter(destination -> Arrays.equals(destination.getHash(), destinationHash))
                .findFirst()
                .orElse(null);
        if (nonNull(localDestination)) {
            localDestination.announce(true, tag, attachedInterface);

            log.debug("Answering path request for {} on {}, destination is local to this system",
                    Hex.encodeHexString(destinationHash), attachedInterface);
        } else if (
                (getOwner().isTransportEnabled() || isFromLocalClient)
                        && destinationTable.containsKey(Hex.encodeHexString(destinationHash))
        ) {
            var hopEntry = destinationTable.get(Hex.encodeHexString(destinationHash));
            var packet = hopEntry.getPacket();
            var nextHop = hopEntry.getVia();
            var receivedFrom = hopEntry.getPacket().getTransportId(); //todo в питоне тут ошибка

            if (nonNull(requestorTransportId) && Arrays.equals(requestorTransportId, nextHop)) {
                // TODO: Find a bandwidth efficient way to invalidate our
                // known path on this signal. The obvious way of signing
                // path requests with transport instance keys is quite
                // inefficient. There is probably a better way. Doing
                // path invalidation here would decrease the network
                // convergence time. Maybe just drop it?
                log.debug("Not answering path request for {}, since next hop is the requestor", Hex.encodeHexString(destinationHash));
            } else {
                log.debug("Answering path request for {} on {}", Hex.encodeHexString(destinationHash), attachedInterface);

                var now = Instant.now();
                var retries = PATHFINDER_R;
                var localRebroadcasts = 0;
                var blockRebroadcasts = true;
                var announceHops = packet.getHops();
                var retransmitTimeout = isFromLocalClient ? now : now.plusMillis(PATH_REQUEST_GRACE);

                // This handles an edge case where a peer sends a past
                // request for a destination just after an announce for
                // said destination has arrived, but before it has been
                // rebroadcast locally. In such a case the actual announce
                // is temporarily held, and then reinserted when the path
                // request has been served to the peer.
                if (announceTable.containsKey(Hex.encodeHexString(destinationHash))) {
                    var heldEntry = announceTable.get(Hex.encodeHexString(destinationHash));
                    heldAnnounces.put(Hex.encodeHexString(destinationHash), heldEntry);
                }

                announceTable.put(
                        Hex.encodeHexString(destinationHash),
                        AnnounceEntry.builder()
                                .timestamp(now)
                                .retransmitTimeout(retransmitTimeout)
                                .retries(retries)
                                .transportId(receivedFrom)
                                .hops(announceHops)
                                .localRebroadcasts(localRebroadcasts)
                                .blockRebroadcasts(blockRebroadcasts)
                                .packet(packet)
                                .attachedInterface(attachedInterface)
                                .build()
                );
            }
        } else if (isFromLocalClient) {
            //Forward path request on all interfaces except the local client
            log.debug("Forwarding path request from local client for {} on {} to all other interfaces",
                    Hex.encodeHexString(destinationHash), attachedInterface);
            var requestTag = getRandomHash();
            for (ConnectionInterface connectionInterface : interfaces) {
                if (isFalse(Objects.equals(connectionInterface, attachedInterface))) {
                    requestPath(destinationHash, connectionInterface, requestTag, false);
                }
            }
        } else if (shouldSearchForUnknown) {
            if (discoveryPathRequests.containsKey(Hex.encodeHexString(destinationHash))) {
                log.debug("There is already a waiting path request for {} on behalf of path request on {}",
                        Hex.encodeHexString(destinationHash), attachedInterface);

            } else {
                //Forward path request on all interfaces except the requestor interface
                log.debug("Attempting to discover unknown path to {} on behalf of path request on {}",
                        Hex.encodeHexString(destinationHash), attachedInterface);

                discoveryPathRequests.put(Hex.encodeHexString(destinationHash),
                        PathRequestEntry.builder()
                                .destinationHash(destinationHash)
                                .timeout(Instant.now().plusSeconds(PATH_REQUEST_TIMEOUT))
                                .requestingInterface(attachedInterface)
                                .build()
                );

                for (ConnectionInterface connectionInterface : interfaces) {
                    if (isFalse(Objects.equals(connectionInterface, attachedInterface))) {
                        //Use the previously extracted tag from this path request
                        // on the new path requests as well, to avoid potential loops
                        requestPath(destinationHash, connectionInterface, tag, true);
                    }
                }
            }
        } else if (isFalse(isFromLocalClient) && localClientInterfaces.size() > 0) {
            //Forward the path request on all local client interfaces
            log.debug("Forwarding path request for {} on {} to local clients", Hex.encodeHexString(destinationHash), attachedInterface);
            for (ConnectionInterface connectionInterface : localClientInterfaces) {
                requestPath(destinationHash, connectionInterface, null, false);
            }
        } else {
            log.debug("Ignoring path request for {} on {}, no path known", Hex.encodeHexString(destinationHash), attachedInterface);
        }
    }

    /**
     * Requests a path to the destination from the network. If
     * another reachable peer on the network knows a path, it
     * will announce it.
     *
     * @param destinationHash non null
     * @param onInterface     default is null. If specified, the path request will only be sent on this interface.
     *                        In normal use, Reticulum handles this automatically, and this parameter should not be used
     * @param tag             default is null
     * @param recursive       default is false
     */
    private void requestPath(
            @NonNull byte[] destinationHash,
            ConnectionInterface onInterface,
            byte[] tag,
            boolean recursive
    ) {
        var requestTag = Objects.requireNonNullElseGet(tag, IdentityUtils::getRandomHash);
        var pathRequestData = owner.isTransportEnabled()
                ? concatArrays(destinationHash, identity.getHash(), requestTag)
                : concatArrays(destinationHash, requestTag);

        var pathRequestDst = new Destination(null, OUT, PLAIN, APP_NAME, "path", "request");
        var packet = new Packet(pathRequestDst, pathRequestData, DATA, onInterface);

        if (nonNull(onInterface) && recursive) {
            var queuedAnnounces = CollectionUtils.isNotEmpty(onInterface.getAnnounceQueue());
            if (queuedAnnounces) {
                log.debug("Blocking recursive path request on {}  due to queued announces", onInterface);
                return;
            } else {
                var now = Instant.now();
                if (now.isBefore(onInterface.getAnnounceAllowedAt())) {
                    log.debug("Blocking recursive path request on {} due to active announce cap", onInterface);
                    return;
                } else {
                    var txTime = (pathRequestData.length + HEADER_MINSIZE) * 8 / onInterface.getBitrate();
                    var waitTime = (long) (txTime / onInterface.getAnnounceCap());
                    onInterface.setAnnounceAllowedAt(now.plusSeconds(waitTime));
                }
            }
        }

        packet.send();
        pathRequests.put(Hex.encodeHexString(destinationHash), Instant.now());
    }

    private void tunnelSynthesizeHandler(byte[] data, Packet packet) {
        try {
            var expectedLength = (KEYSIZE + HASHLENGTH + TRUNCATED_HASHLENGTH + SIGLENGTH) / 8;
            if (getLength(data) == expectedLength) {
                var publicKey = subarray(data, 0, KEYSIZE / 8);
                var interfaceHash = subarray(data, KEYSIZE / 8, (KEYSIZE + HASHLENGTH) / 8);
                var tunnelIdData = concatArrays(publicKey, interfaceHash);
                var tunnelId = fullHash(tunnelIdData);
                var randomHash = subarray(data, (KEYSIZE + HASHLENGTH) / 8, (KEYSIZE + HASHLENGTH + TRUNCATED_HASHLENGTH) / 8);

                var signature = subarray(data, (KEYSIZE + HASHLENGTH + TRUNCATED_HASHLENGTH) / 8, expectedLength);
                var signedData = concatArrays(tunnelIdData, randomHash);

                var remoteTransportIdentity = new Identity(false);
                remoteTransportIdentity.loadPublicKey(publicKey);

                if (remoteTransportIdentity.validate(signature, signedData)) {
                    handleTunnel(tunnelId, packet.getReceivingInterface());
                }
            }
        } catch (Exception e) {
            log.error("An error occurred while validating tunnel establishment packet.", e);
        }
    }

    @SneakyThrows
    private void handleTunnel(byte[] tunnelId, ConnectionInterface iface) {
        var expires = Instant.now().plusSeconds(DESTINATION_TIMEOUT);
        Map<String, Hops> paths = new HashMap<>();
        if (isFalse(tunnels.containsKey(Hex.encodeHexString(tunnelId)))) {
            log.debug("Tunnel endpoint {} established.", Hex.encodeHexString(tunnelId));
            iface.setTunnelId(tunnelId);
            tunnels.put(
                    Hex.encodeHexString(tunnelId),
                    Tunnel.builder()
                            .tunnelId(tunnelId)
                            .expires(expires)
                            .tunnelPaths(paths)
                            .anInterface(iface)
                            .build()
            );
        } else {
            log.debug("Tunnel endpoint {} reappeared. Restoring paths...", Hex.encodeHexString(tunnelId));
            var tunnelEntry = tunnels.get(Hex.encodeHexString(tunnelId));
            tunnelEntry.setAnInterface(iface);
            tunnelEntry.setExpires(expires);
            iface.setTunnelId(tunnelId);
            paths = tunnelEntry.getTunnelPaths();

            var deprecatedPaths = new LinkedList<byte[]>();
            for (Map.Entry<String, Hops> entry : paths.entrySet()) {
                var destinationHash = Hex.decodeHex(entry.getKey());
                var pathEntry = entry.getValue();
                var packet = pathEntry.getPacket();
                var announceHops = pathEntry.getHops();
                expires = pathEntry.getExpires();

                var shouldAdd = false;
                if (destinationTable.containsKey(Hex.encodeHexString(destinationHash))) {
                    var oldEntry = destinationTable.get(Hex.encodeHexString(destinationHash));
                    var oldHops = oldEntry.getHops();
                    var oldExpires = oldEntry.getExpires();
                    if (announceHops < oldHops || Instant.now().isAfter(oldExpires)) {
                        shouldAdd = true;
                    } else {
                        log.debug("Did not restore path to {} because a newer path with fewer hops exist", Hex.encodeHexString(packet.getDestinationHash()));
                    }
                } else {
                    if (Instant.now().isBefore(expires)) {
                        shouldAdd = true;
                    } else {
                        log.debug("Did not restore path to {} because it has expired", Hex.encodeHexString(packet.getDestinationHash()));
                    }
                }

                if (shouldAdd) {
                    destinationTable.put(
                            Hex.encodeHexString(destinationHash),
                            pathEntry.toBuilder()
                                    .timestamp(Instant.now())
                                    .anInterface(iface)
                                    .build()
                    );

                    log.debug(
                            "Restored path to {} is now {} hops away via {}",
                            Hex.encodeHexString(packet.getDestinationHash()),
                            announceHops,
                            Hex.encodeHexString(pathEntry.getVia())
                    );
                } else {
                    deprecatedPaths.add(destinationHash);
                }
            }

            for (byte[] deprecatedPath : deprecatedPaths) {
                log.debug("Removing path to {} from tunnel {}", Hex.encodeHexString(deprecatedPath), Hex.encodeHexString(tunnelId));
                paths.remove(Hex.encodeHexString(deprecatedPath));
            }
        }
    }

    private void jobs() {
        List<Packet> outgoing = new LinkedList<>();
        List<byte[]> pathRequestList = new LinkedList<>();

        try {
            if (jobsLock.tryLock()) {

                //Process active and pending link lists
                if (Instant.now().isAfter(linksLastChecked.get().plusMillis(LINKS_CHECK_INTERVAL))) {
                    for (Link link : pendingLinks) {
                        if (link.getStatus() == CLOSED) {
                            // If we are not a Transport Instance, finding a pending link
                            // that was never activated will trigger an expiry of the path
                            // to the destination, and an attempt to rediscover the path.
                            if (isFalse(owner.isTransportEnabled())) {
                                expirePath(link.getDestination().getHash());

                                // If we are connected to a shared instance, it will take
                                // care of sending out a new path request. If not, we will
                                // send one directly.
                                if (isFalse(owner.isConnectedToSharedInstance())) {
                                    var lastPathRequest = Instant.EPOCH;
                                    if (pathRequests.containsKey(Hex.encodeHexString(link.getDestination().getHash()))) {
                                        lastPathRequest = pathRequests.get(Hex.encodeHexString(link.getDestination().getHash()));
                                    }

                                    if (Duration.between(lastPathRequest, Instant.now()).toSeconds() > PATH_REQUEST_MI) {
                                        log.debug("Trying to rediscover path for {} since an attempted link was never established",
                                                Hex.encodeHexString(link.getDestination().getHash()));
                                        if (pathRequestList.stream().noneMatch(bytes -> Arrays.equals(bytes, link.getDestination().getHash()))) {
                                            pathRequestList.add(link.getDestination().getHash());
                                        }
                                    }
                                }
                            }
                            pendingLinks.remove(link);
                        }
                    }

                    activeLinks.removeIf(link -> link.getStatus() == CLOSED);

                    linksLastChecked.set(Instant.now());
                }

                //Process receipts list for timed-out packets
                if (Instant.now().isAfter(announcesLastChecked.get().plusMillis(ANNOUNCES_CHECK_INTERVAL))) {
                    for (String destinationHashString : announceTable.keySet()) {
                        var announceEntry = announceTable.get(destinationHashString);
                        if (announceEntry.getRetries() > PATHFINDER_R) {
                            log.debug("Completed announce processing for {}, retry limit reached", destinationHashString);
                            announceTable.remove(destinationHashString);
                            break;
                        } else {
                            if (Instant.now().isAfter(announceEntry.getRetransmitTimeout())) {
                                announceEntry.setRetransmitTimeout(Instant.now().plusSeconds(PATHFINDER_G).plusMillis(PATHFINDER_RW));
                                announceEntry.setRetries(announceEntry.getRetries() + 1);
                                var packet = announceEntry.getPacket();
                                var blockRebroadcasts = announceEntry.isBlockRebroadcasts();
                                var attachedInterface = announceEntry.getAttachedInterface();
                                var announceContext = blockRebroadcasts ? PATH_RESPONSE : NONE;
                                var announceData = packet.getData();
                                var announceIdentity = recall(packet.getDestinationHash());
                                var announceDestination = new Destination(announceIdentity, OUT, SINGLE, "unknown", "unknown");
                                announceDestination.setHash(packet.getDestinationHash());
                                announceDestination.setHexHash(Hex.encodeHexString(announceDestination.getHash()));

                                var newPacket = new Packet(
                                        announceDestination,
                                        announceData,
                                        ANNOUNCE,
                                        announceContext,
                                        HEADER_2,
                                        TRANSPORT,
                                        identity.getHash(),
                                        attachedInterface
                                );
                                newPacket.setHops(announceEntry.getHops());

                                if (blockRebroadcasts) {
                                    log.debug("Rebroadcasting announce as path response for {} with hop count {}",
                                            announceDestination.getHexHash(), newPacket.getHops());
                                } else {
                                    log.debug("Rebroadcasting announce for {} with hop count {}",
                                            announceDestination.getHexHash(), newPacket.getHops());
                                }

                                outgoing.add(newPacket);

                                // This handles an edge case where a peer sends a past
                                // request for a destination just after an announce for
                                // said destination has arrived, but before it has been
                                // rebroadcast locally. In such a case the actual announce
                                // is temporarily held, and then reinserted when the path
                                // request has been served to the peer.
                                for (String destinationHashHex : heldAnnounces.keySet()) {
                                    announceTable.put(destinationHashHex, heldAnnounces.get(destinationHashHex));
                                    log.debug("Reinserting held announce into table");
                                }
                            }
                        }
                    }
                    announcesLastChecked.set(Instant.now());
                }

                //Cull the packet hashlist if it has reached its max size
                if (discoveryPrTags.size() > MAX_PR_TAGS) {
                    var list = discoveryPrTags.subList(discoveryPrTags.size() - MAX_PR_TAGS, discoveryPrTags.size() - 1);
                    discoveryPrTags.clear();
                    discoveryPrTags.addAll(list);
                }

                if (Instant.now().isAfter(tablesLastCulled.get().plusMillis(TABLES_CULL_INTERVAL))) {
                    //Cull the reverse table according to timeout
                    List<String> staleReverseEntries = new LinkedList<>();
                    for (String truncatedPacketHashHex : reverseTable.keySet()) {
                        var reverseEntry = reverseTable.get(truncatedPacketHashHex);
                        if (Instant.now().isAfter(reverseEntry.getTimestamp().plusSeconds(REVERSE_TIMEOUT))) {
                            staleReverseEntries.add(truncatedPacketHashHex);
                        }
                    }

                    //Cull the link table according to timeout
                    List<String> staleLinks = new LinkedList<>();
                    for (String linkIdHex : linkTable.keySet()) {
                        var linkEntry = linkTable.get(linkIdHex);

                        if (linkEntry.isValidated()) {
                            if (Instant.now().isAfter(linkEntry.getTimestamp().plusSeconds(LINK_TIMEOUT))) {
                                staleLinks.add(linkIdHex);
                            }
                        } else {
                            if (Instant.now().isAfter(linkEntry.getProofTimestamp())) {
                                staleLinks.add(linkIdHex);

                                var lastPathRequest = pathRequests.getOrDefault(
                                        Hex.encodeHexString(linkEntry.getDestinationHash()),
                                        Instant.EPOCH
                                );

                                // If this link request was originated from a local client
                                // attempt to rediscover a path to the destination, if this
                                // has not already happened recently.
                                var lrTokenHops = linkEntry.getHops();
                                if (lrTokenHops == 0 && Duration.between(lastPathRequest, Instant.now()).toSeconds() > PATH_REQUEST_MI) {
                                    log.debug("Trying to rediscover path for {} since an attempted local client link was never established",
                                            Hex.encodeHexString(linkEntry.getDestinationHash()));
                                    if (pathRequestList.stream().noneMatch(bytes -> Arrays.equals(bytes, linkEntry.getDestinationHash()))) {
                                        pathRequestList.add(linkEntry.getDestinationHash());
                                    }

                                    if (isFalse(owner.isTransportEnabled())) {
                                        // Drop current path if we are not a transport instance, to
                                        // allow using higher-hop count paths or reused announces
                                        // from newly adjacent transport instances.
                                        expirePath(linkEntry.getDestinationHash());
                                    }
                                }
                            }
                        }
                    }

                    //Cull the path table
                    List<String> stalePaths = new LinkedList<>();
                    for (String destinationHashHex : destinationTable.keySet()) {
                        var destinationEntry = destinationTable.get(destinationHashHex);
                        var attachedInterface = destinationEntry.getInterface();

                        Instant destinationExpiry;
                        if (nonNull(attachedInterface) && attachedInterface.getMode() == MODE_ACCESS_POINT) {
                            destinationExpiry = destinationEntry.getTimestamp().plusSeconds(AP_PATH_TIME);
                        } else if (nonNull(attachedInterface) && attachedInterface.getMode() == MODE_ROAMING) {
                            destinationExpiry = destinationEntry.getTimestamp().plusSeconds(ROAMING_PATH_TIME);
                        } else {
                            destinationExpiry = destinationEntry.getTimestamp().plusSeconds(DESTINATION_TIMEOUT);
                        }

                        if (Instant.now().isAfter(destinationExpiry)) {
                            stalePaths.add(destinationHashHex);
                            log.debug("Path to {} timed out and was removed", destinationHashHex);
                        } else if (isFalse(interfaces.contains(attachedInterface))) {
                            stalePaths.add(destinationHashHex);
                            log.debug("Path to {} was removed since the attached interface no longer exists", destinationHashHex);
                        }
                    }

                    //Cull the pending discovery path requests table
                    List<String> staleDiscoveryPathRequests = new LinkedList<>();
                    for (String destinationHashHex : discoveryPathRequests.keySet()) {
                        var entry = discoveryPathRequests.get(destinationHashHex);

                        if (Instant.now().isAfter(entry.getTimeout())) {
                            staleDiscoveryPathRequests.add(destinationHashHex);
                            log.debug("Waiting path request for {} timed out and was removed", destinationHashHex);
                        }
                    }

                    //Cull the tunnel table
                    List<String> staleTunnels = new LinkedList<>();
                    var ti = 0;
                    for (String tunnelIdHex : tunnels.keySet()) {
                        var tunnelEntry = tunnels.get(tunnelIdHex);

                        var expires = tunnelEntry.getExpires();
                        if (Instant.now().isAfter(expires)) {
                            staleTunnels.add(tunnelIdHex);
                            log.debug("Tunnel {} timed out and was removed", tunnelIdHex);
                        } else {
                            List<String> staleTunnelPaths = new LinkedList<>();
                            var tunnelPaths = tunnelEntry.getTunnelPaths();
                            for (String tunnelPath : tunnelPaths.keySet()) {
                                var tunnelPathEntry = tunnelPaths.get(tunnelPath);

                                if (Instant.now().isAfter(tunnelPathEntry.getTimestamp().plusSeconds(DESTINATION_TIMEOUT))) {
                                    staleTunnelPaths.add(tunnelPath);
                                    log.debug("Tunnel path to {} timed out and was removed", tunnelPath);
                                }
                            }

                            for (String staleTunnelPath : staleTunnelPaths) {
                                tunnelPaths.remove(staleTunnelPath);
                                ti++;
                            }
                        }
                    }

                    if (ti > 0) {
                        log.debug("Removed {} tunnel paths", ti);
                    }

                    staleReverseEntries.forEach(reverseTable::remove);
                    if (isFalse(staleReverseEntries.isEmpty())) {
                        log.debug("Released {} reverse table entries", staleReverseEntries.size());
                    }

                    staleLinks.forEach(linkTable::remove);
                    if (isFalse(staleLinks.isEmpty())) {
                        log.debug("Released {} links", staleLinks.size());
                    }

                    stalePaths.forEach(destinationTable::remove);
                    if (isFalse(stalePaths.isEmpty())) {
                        log.debug("Removed {} waiting path requests", stalePaths.size());
                    }

                    staleDiscoveryPathRequests.forEach(discoveryPathRequests::remove);
                    if (isFalse(staleDiscoveryPathRequests.isEmpty())) {
                        log.debug("Removed {} waiting path requests", staleDiscoveryPathRequests.size());
                    }

                    staleTunnels.forEach(tunnels::remove);
                    if (isFalse(staleTunnels.isEmpty())) {
                        log.debug("Removed {} tunnels", staleTunnels.size());
                    }

                    tablesLastCulled.set(Instant.now());
                }
            } else {
                //Transport jobs were locked, do nothing
            }
        } catch (Exception e) {
            log.error("An exception occurred while running Transport jobs.", e);
        } finally {
            jobsLock.unlock();
        }

        outgoing.forEach(Packet::send);
        pathRequestList.forEach(destinationHash -> requestPath(destinationHash, null, null, false));
    }

    private boolean expirePath(byte[] destinationHash) {
        if (destinationTable.containsKey(Hex.encodeHexString(destinationHash))) {
            var entry = destinationTable.get(Hex.encodeHexString(destinationHash));
            entry.setTimestamp(Instant.EPOCH);
            tablesLastCulled.set(Instant.EPOCH);

            return true;
        }

        return false;
    }

    public synchronized void synthesizeTunnel(@NonNull final ConnectionInterface iface) {
        var interfaceHash = iface.getHash();
        var publicKey = identity.getPublicKey();
        var randomHash = getRandomHash();

        var tunnelIdData = concatArrays(publicKey, interfaceHash);
        var tunnelId = fullHash(tunnelIdData);

        var signedData = concatArrays(tunnelIdData, randomHash);
        var signature = identity.sign(signedData);

        var data = concatArrays(signedData, signature);

        var tnlSnthDst = new Destination(null, OUT, PLAIN, APP_NAME, "tunnel", "synthesize");

        var packet = new Packet(tnlSnthDst, data, DATA, iface);
        packet.send();

        iface.setWantsTunnel(false);
    }
}
