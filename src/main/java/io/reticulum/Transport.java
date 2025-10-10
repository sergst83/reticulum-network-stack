package io.reticulum;

import io.reticulum.constant.TransportConstant;
import io.reticulum.destination.Destination;
import io.reticulum.identity.Identity;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.interfaces.InterfaceMode;
import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import io.reticulum.packet.PacketReceipt;
import io.reticulum.packet.PacketReceiptStatus;
import io.reticulum.packet.PacketType;
import io.reticulum.packet.data.DataPacket;
import io.reticulum.packet.data.DataPacketConverter;
import io.reticulum.storage.Storage;
import io.reticulum.storage.entity.DestinationTable;
import io.reticulum.storage.entity.HopEntity;
import io.reticulum.storage.entity.PacketCache;
import io.reticulum.storage.entity.TunnelEntity;
import io.reticulum.transport.AnnounceEntry;
import io.reticulum.transport.AnnounceHandler;
import io.reticulum.transport.AnnounceQueueEntry;
import io.reticulum.transport.Hops;
import io.reticulum.transport.LinkEntry;
import io.reticulum.transport.PathRequestEntry;
import io.reticulum.transport.RateEntry;
import io.reticulum.transport.ReversEntry;
import io.reticulum.transport.TransportState;
import io.reticulum.transport.Tunnel;
import io.reticulum.utils.IdentityUtils;
import io.reticulum.utils.LinkUtils;
import io.reticulum.utils.Scheduler;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
import java.util.function.Consumer;

import static io.reticulum.constant.IdentityConstant.HASHLENGTH;
import static io.reticulum.constant.IdentityConstant.KEYSIZE;
import static io.reticulum.constant.IdentityConstant.NAME_HASH_LENGTH;
import static io.reticulum.constant.IdentityConstant.SIGLENGTH;
import static io.reticulum.constant.LinkConstant.ECPUBSIZE;
import static io.reticulum.constant.LinkConstant.ESTABLISHMENT_TIMEOUT_PER_HOP;
import static io.reticulum.constant.LinkConstant.LINK_MTU_SIZE;
import static io.reticulum.constant.PacketConstant.EXPL_LENGTH;
import static io.reticulum.constant.ReticulumConstant.ANNOUNCE_CAP;
import static io.reticulum.constant.ReticulumConstant.DEFAULT_PER_HOP_TIMEOUT;
import static io.reticulum.constant.ReticulumConstant.HEADER_MINSIZE;
import static io.reticulum.constant.ReticulumConstant.MAX_QUEUED_ANNOUNCES;
import static io.reticulum.constant.ReticulumConstant.MTU;
import static io.reticulum.constant.ReticulumConstant.TRUNCATED_HASHLENGTH;
import static io.reticulum.constant.TransportConstant.ANNOUNCES_CHECK_INTERVAL;
import static io.reticulum.constant.TransportConstant.APP_NAME;
import static io.reticulum.constant.TransportConstant.AP_PATH_TIME;
import static io.reticulum.constant.TransportConstant.DESTINATION_TIMEOUT;
import static io.reticulum.constant.TransportConstant.DISCOVER_PATHS_FOR;
import static io.reticulum.constant.TransportConstant.HASHLIST_MAXSIZE;
import static io.reticulum.constant.TransportConstant.JOB_INTERVAL;
import static io.reticulum.constant.TransportConstant.LINKS_CHECK_INTERVAL;
import static io.reticulum.constant.TransportConstant.LINK_TIMEOUT;
import static io.reticulum.constant.TransportConstant.LOCAL_CLIENT_CACHE_MAXSIZE;
import static io.reticulum.constant.TransportConstant.LOCAL_REBROADCASTS_MAX;
import static io.reticulum.constant.TransportConstant.MAX_PR_TAGS;
import static io.reticulum.constant.TransportConstant.MAX_RATE_TIMESTAMPS;
import static io.reticulum.constant.TransportConstant.MAX_RECEIPTS;
import static io.reticulum.constant.TransportConstant.PATHFINDER_E;
import static io.reticulum.constant.TransportConstant.PATHFINDER_G;
import static io.reticulum.constant.TransportConstant.PATHFINDER_M;
import static io.reticulum.constant.TransportConstant.PATHFINDER_R;
import static io.reticulum.constant.TransportConstant.PATHFINDER_RW;
import static io.reticulum.constant.TransportConstant.PATH_REQUEST_GRACE;
import static io.reticulum.constant.TransportConstant.PATH_REQUEST_RG;
import static io.reticulum.constant.TransportConstant.PATH_REQUEST_MI;
import static io.reticulum.constant.TransportConstant.PATH_REQUEST_TIMEOUT;
import static io.reticulum.constant.TransportConstant.RECEIPTS_CHECK_INTERVAL;
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
import static io.reticulum.storage.Storage.DESTINATION_TABLE;
import static io.reticulum.transport.TransportState.STATE_RESPONSIVE;
import static io.reticulum.transport.TransportState.STATE_UNKNOWN;
import static io.reticulum.transport.TransportState.STATE_UNRESPONSIVE;
import static io.reticulum.transport.TransportType.BROADCAST;
import static io.reticulum.transport.TransportType.TRANSPORT;
import static io.reticulum.utils.DestinationUtils.hashFromNameAndIdentity;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static io.reticulum.utils.IdentityUtils.fullHash;
import static io.reticulum.utils.IdentityUtils.getRandomHash;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.commons.codec.binary.Hex.decodeHex;

import static org.apache.commons.codec.binary.Hex.encodeHexString;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;
import static org.apache.commons.lang3.ArrayUtils.getLength;
import static org.apache.commons.lang3.ArrayUtils.subarray;
import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

@Slf4j
public final class Transport implements ExitHandler {
    private final ReentrantLock savingPathTableLock = new ReentrantLock();
    private final ReentrantLock savingTunnelTableLock = new ReentrantLock();
    private final ReentrantLock jobsLock = new ReentrantLock(true);

    private final AtomicReference<Instant> linksLastChecked = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> announcesLastChecked = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> receiptsLastChecked = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> tablesLastCulled = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> interfaceLastJobs = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Duration> interfaceJobsInterval = new AtomicReference<>(Duration.ofSeconds(5));

    private static volatile Transport INSTANCE;
    @Getter
    private final Reticulum owner;
    @Getter
    private Identity identity;
    private final Storage storage;

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
    private final Map<String, TransportState> pathStates = new ConcurrentHashMap<>();
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
    private final Map<String, byte[]> packetHashMap = new ConcurrentHashMap<>();
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
    private final Deque<Pair<byte[], Integer>> localClientQCache = new ConcurrentLinkedDeque<>();

    @SneakyThrows
    private void init() {
        identity = Optional.ofNullable(storage.getIdentity())
                .map(i -> {
                    log.debug("Loaded Transport Identity from storage");

                    return i;
                })
                .orElseGet(() -> {
                    log.debug("No valid Transport Identity in storage, creating...");
                    var idt = new Identity();
                    storage.saveIdentity(idt);

                    return idt;
                });

        if (isFalse(owner.isConnectedToSharedInstance())) {
            packetHashMap.clear();
            packetHashMap.putAll(storage.loadAllPacketHash());
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
            var dtList = storage.getDestinationTables();
            if (isNotEmpty(dtList) && isFalse(owner.isConnectedToSharedInstance())) {
                for (DestinationTable entry : dtList) {
                    var destinationHash = entry.getDestinationHash();
                    var hopEntry = entry.getHop();
                    if (getLength(decodeHex(destinationHash)) == TRUNCATED_HASHLENGTH / 8) {
                        var receivingInterface = findInterfaceFromHash(hopEntry.getInterfaceHash());
                        var announcePacket = getCachedPacket(hopEntry.getPacketHash());
                        if (nonNull(announcePacket) && nonNull(receivingInterface)) {
                            announcePacket.unpack();
                            // We increase the hops, since reading a packet
                            // from cache is equivalent to receiving it again
                            // over an interface. It is cached with it's non-increased hop-count.
                            announcePacket.setHops(announcePacket.getHops() + 1);
                            destinationTable.put(
                                    destinationHash,
                                    Hops.builder()
                                            .timestamp(hopEntry.getTimestamp())
                                            .via(hopEntry.getVia())
                                            .expires(hopEntry.getExpires())
                                            .hops(hopEntry.getHops())
                                            .randomBlobs(hopEntry.getRandomBlobs())
                                            .anInterface(receivingInterface)
                                            .packet(announcePacket)
                                            .build()
                            );
                            log.debug("Loaded path table entry for {} from storage {}", destinationHash, DESTINATION_TABLE);
                        } else {
                            log.debug("Could not reconstruct path table entry from storage for {}", destinationHash);
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
            }

            var tunnelList = storage.getTunnelTables();
            if (isNotEmpty(tunnelList) && isFalse(owner.isConnectedToSharedInstance())) {
                for (TunnelEntity tunnelEntity : tunnelList) {
                    var tunnelPaths = new HashMap<String, Hops>();
                    MapUtils.emptyIfNull(tunnelEntity.getTunnelPaths()).forEach((destinationHash, hopEntry) -> {
                        var receivingInterface = findInterfaceFromHash(hopEntry.getInterfaceHash());
                        var announcePacket = getCachedPacket(hopEntry.getPacketHash());
                        if (nonNull(announcePacket)) {
                            // We increase the hops, since reading a packet
                            // from cache is equivalent to receiving it again
                            // over an interface. It is cached with it's non-increased hop-count.
                            announcePacket.setHops(announcePacket.getHops() + 1);

                            var tunnelPath = Hops.builder()
                                    .timestamp(hopEntry.getTimestamp())
                                    .hops(hopEntry.getHops())
                                    .via(hopEntry.getVia())
                                    .expires(hopEntry.getExpires())
                                    .randomBlobs(hopEntry.getRandomBlobs())
                                    .anInterface(receivingInterface)
                                    .packet(announcePacket)
                                    .build();
                            tunnelPaths.put(destinationHash, tunnelPath);
                        }
                    });
                    tunnels.put(
                            tunnelEntity.getTunnelIdHex(),
                            Tunnel.builder()
                                    .tunnelId(tunnelEntity.getTunnelId())
                                    .tunnelPaths(tunnelPaths)
                                    .expires(tunnelEntity.getExpires())
                                    .anInterface(findInterfaceFromHash(tunnelEntity.getInterfaceHash()))
                                    .build()
                    );
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

    private Transport(@NonNull final Reticulum reticulum) {
        this.owner = reticulum;
        this.storage = Storage.init(reticulum.getStoragePath());
    }

    public static Transport start(@NonNull final Reticulum reticulum) {
        var transport = INSTANCE;
        if (transport == null) {
            synchronized (Transport.class) {
                transport = INSTANCE;
                if (transport == null) {
                    INSTANCE = transport = new Transport(reticulum);
                    transport.init();
                }
            }
        }

        Scheduler.scheduleWithFixedDelaySafe(() -> INSTANCE.jobs(), JOB_INTERVAL, MILLISECONDS);

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

        detachableInterfaces.forEach(anInterface -> {
            try {
                anInterface.detach();
            } catch (Exception e) {
                log.error("An error occurred while detaching {}.", anInterface, e);
            }
        });
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

        var saveStart = Instant.now();
        if (isFalse(owner.isTransportEnabled())) {
            packetHashMap.clear();
        }
        log.debug("Saving packet hashlist to storage...");
        storage.saveAllPacketHash(packetHashMap);
        log.debug("Saved packet hashlist in {} ms", Duration.between(saveStart, Instant.now()).toMillis());
    }

    private void savePathTable() {
        if (owner.isConnectedToSharedInstance()) {
            return;
        }

        try {
            if (savingPathTableLock.tryLock(5, TimeUnit.SECONDS)) {
                var saveStart = Instant.now();

                log.debug("Saving path table to storage...");

                var dtList = new LinkedList<DestinationTable>();
                for (String destinationHash : destinationTable.keySet()) {
                    // Get the destination entry from the destination table
                    var de = destinationTable.get(destinationHash);
                    var interfaceHash = de.getInterface().getHash();

                    //Only store destination table entry if the associated interface is still active
                    var iface = findInterfaceFromHash(interfaceHash);
                    if (nonNull(iface)) {
                        //Get the destination entry from the destination table
                        dtList.add(
                                DestinationTable.builder()
                                        .destinationHash(destinationHash)
                                        .hop(
                                                HopEntity.builder()
                                                        .timestamp(de.getTimestamp())
                                                        .via(de.getVia())
                                                        .hops(de.getHops())
                                                        .expires(de.getExpires())
                                                        .randomBlobs(de.getRandomBlobs())
                                                        .interfaceHash(interfaceHash)
                                                        .packetHash(de.getPacket().getHash())
                                                        .build()
                                        )
                                        .build()
                        );
                        cache(de.getPacket(), true);
                    }
                }
                storage.saveDestinationTables(dtList);

                log.debug("Saved {}  path table entries in {} ms", dtList, Duration.between(saveStart, Instant.now()).toMillis());
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
                String interfaceName = null;
                if (nonNull(packet.getReceivingInterface())) {
                    interfaceName = packet.getReceivingInterface().getInterfaceName();
                }

                storage.savePacketCache(
                        PacketCache.builder()
                                .interfaceName(interfaceName)
                                .raw(packet.getRaw())
                                .packetHash(encodeHexString(packet.getHash()))
                                .build()
                );
            } catch (Exception e) {
                log.error("Error writing packet to cache", e);
            }
        }
    }

    private Packet getCachedPacket(byte[] packetHash) {
        return getCachedPacket(packetHash, null);
    }

    private Packet getCachedPacket(byte[] packetHash, PacketType packetType) {
        var paketCache = storage.getPacketCache(encodeHexString(packetHash));
        //if (isNull(paketCache) && (packetType != ANNOUNCE)) {
        if (isNull(paketCache)) {
            return null;
        }

        var packet = new Packet(paketCache.getRaw());
        //if (packetType == ANNOUNCE) {
        //    var announceEntry = announceTable.get(encodeHexString(packetHash));
        //    if (nonNull(announceEntry)) {
        //        packet = announceEntry.getPacket();
        //    }
        //}

        interfaces.stream()
                .filter(i -> StringUtils.equals(i.getInterfaceName(), paketCache.getInterfaceName()))
                .findAny()
                .ifPresent(packet::setReceivingInterface);

        return packet;
    }

    private boolean shouldCache(Packet packet) {
        // TODO: Rework the caching system. It's currently
        // not very useful to even cache Resource proofs,
        // disabling it for now, until redesigned.
        // return packet.getContext() == RESOURCE_PRF;

        return false;
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

                var serialisedTunnels = new LinkedList<TunnelEntity>();
                for (String tunnelId : tunnels.keySet()) {
                    var te = tunnels.get(tunnelId);
                    var iface = te.getInterface();
                    var tunnelPaths = te.getTunnelPaths();
                    var expires = te.getExpires();

                    byte[] interfaceHash = null;
                    if (nonNull(iface)) {
                        interfaceHash = iface.getHash();
                    }

                    var serialisedPaths = new HashMap<String, HopEntity>();
                    for (String destinationHash : tunnelPaths.keySet()) {
                        var de = tunnelPaths.get(destinationHash);
                        var hopEntity = HopEntity.builder()
                                .timestamp(de.getTimestamp())
                                .via(de.getVia())
                                .hops(de.getHops())
                                .expires(de.getExpires())
                                .randomBlobs(de.getRandomBlobs())
                                .interfaceHash(interfaceHash)
                                .packetHash(de.getPacket().getHash())
                                .build();
                        serialisedPaths.put(destinationHash, hopEntity);
                        cache(de.getPacket(), true);
                    }

                    serialisedTunnels.add(
                            TunnelEntity.builder()
                                    .expires(expires)
                                    .tunnelIdHex(tunnelId)
                                    .tunnelId(te.getTunnelId())
                                    .tunnelPaths(serialisedPaths)
                                    .build()
                    );
                }
                storage.saveTunnelTable(serialisedTunnels);

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

    // TODO: 12.05.2023 for refactoring.
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
                        var newHeader = new byte[]{(byte) (unmaskedRaw[0] & 0x7f), unmaskedRaw[1]};

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
                    log.trace("The IFAC flag is not set, but should be, drop the packet: {}, iface: {}", raw, iface);
                    return;
                }
            } else {
                //If the interface does not have IFAC enabled, check the received packet IFAC flag.
                if ((raw[0] & 0x80) == 0x80) {
                    //If the flag is set, drop the packet
                    log.trace("Interface does not have IFAC enabled, but the flag is set. Drop the packet: {}, iface: {}", raw, iface);
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
            try {
                TimeUnit.MICROSECONDS.sleep(500);
            } catch (InterruptedException e) {
                log.trace("sleep interrupted");
            }
        }

        if (isNull(identity)) {
            return;
        }

        var packet = new Packet(localRaw);
        if (isFalse(packet.unpack())) {
            jobsLock.unlock();
            return;
        }

        packet.setReceivingInterface(iface);
        packet.setHops(packet.getHops() + 1);

        if (nonNull(iface)) {
            if (nonNull(iface.getRStatRssi())) {
                packet.setRssi(iface.getRStatRssi());
                if (isNotEmpty(localClientInterfaces)) {
                    localClientRssiCache.add(Pair.of(packet.getHash(), packet.getRssi()));

                    while (localClientRssiCache.size() > LOCAL_CLIENT_CACHE_MAXSIZE) {
                        localClientRssiCache.pop();
                    }
                }
            }

            if (nonNull(iface.getRStatSnr())) {
                packet.setSnr(iface.getRStatSnr());
                if (isNotEmpty(localClientInterfaces)) {
                    localClientSnrCache.add(Pair.of(packet.getHash(), packet.getSnr()));

                    while (localClientSnrCache.size() > LOCAL_CLIENT_CACHE_MAXSIZE) {
                        localClientSnrCache.pop();
                    }
                }
            }

            if (nonNull(iface.getRStatQ())) {
                packet.setQ(iface.getRStatQ());
                if (isNotEmpty(localClientInterfaces)) {
                    localClientQCache.push(Pair.of(packet.getPacketHash(), packet.getQ()));

                    while (localClientQCache.size() > LOCAL_CLIENT_CACHE_MAXSIZE) {
                        localClientQCache.pop();
                    }
                }
            }
        }

        if (isNotEmpty(localClientInterfaces)) {
            if (isLocalClientInterface(iface)) {
                packet.setHops(packet.getHops() - 1);
            }
        } else if (interfaceToSharedInstance(iface)) {
            packet.setHops(packet.getHops() - 1);
        }

        if (packetFilter(packet)) {
            // By default, remember packet hashes to avoid routing loops in the network, using the packet filter.
            var rememberPacketHash = true;

            // If this packet belongs to a link in our link table,
            // we'll have to defer adding it to the filter list.
            // In some cases, we might see a packet over a shared-
            // medium interface, belonging to a link that transports
            // or terminates with this instance, but before it would
            // normally reach us. If the packet is appended to the
            // filter list at this point, link transport will break.
            if (linkTable.containsKey(encodeHexString(packet.getDestinationHash()))) {
                rememberPacketHash = false;
            }

            // If this is a link request proof, don't add it until
            // we are sure it's not actually somewhere else in the
            // routing chain.
            if (packet.getPacketType() == PROOF && packet.getContext() == LRPROOF) {
                rememberPacketHash = false;
            }

            if (rememberPacketHash) {
                packetHashMap.put(encodeHexString(packet.getPacketHash()), packet.getPacketHash());
                cache(packet, false);
            }

            //Check special conditions for local clients connected through a shared Reticulum instance
            var fromLocalClient = localClientInterfaces.contains(packet.getReceivingInterface());
            var forLocalClient = packet.getPacketType() != ANNOUNCE
                    && destinationTable.containsKey(encodeHexString(packet.getDestinationHash()))
                    && destinationTable.get(encodeHexString(packet.getDestinationHash())).getHops() == 0;
            var forLocalClientLink = packet.getPacketType() != ANNOUNCE
                    && linkTable.containsKey(encodeHexString(packet.getDestinationHash()))
                    && (
                    localClientInterfaces.contains(linkTable.get(encodeHexString(packet.getDestinationHash())).getNextHopInterface())
                            || localClientInterfaces.contains(linkTable.get(encodeHexString(packet.getDestinationHash())).getReceivingInterface())
            );
            var proofForLocalClient = reverseTable.containsKey(encodeHexString(packet.getDestinationHash()))
                    && localClientInterfaces.contains(reverseTable.get(encodeHexString(packet.getDestinationHash())).getReceivingInterface());

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
                        jobsLock.unlock();
                        return;
                    }
                }

                // If the packet is in transport, check whether we are the designated next hop, and process it accordingly if we are.
                if (nonNull(packet.getTransportId()) && packet.getPacketType() != ANNOUNCE) {
                    if (Arrays.equals(packet.getTransportId(), identity.getHash())) {
                        if (destinationTable.containsKey(encodeHexString(packet.getDestinationHash()))) {
                            var hopsEntry = destinationTable.get(encodeHexString(packet.getDestinationHash()));
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

                            var outboundInterface = destinationTable.get(encodeHexString(packet.getDestinationHash())).getInterface();

                            if (packet.getPacketType() == LINKREQUEST) {
                                var now = Instant.now();
                                var proofTimeout =  now
                                        .plusMillis((long) ESTABLISHMENT_TIMEOUT_PER_HOP * Math.max(1, remainingHops))
                                        .plusSeconds(extraLinkProofTimeout(packet.getReceivingInterface()));

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

//                                linkTable.put(encodeHexString(packet.getTruncatedHash()), linkEntry);
                                // I changed it to destination Hash, because in java we search by string representation and truncatedHash can be != destinationHash
                                linkTable.put(encodeHexString(packet.getDestinationHash()), linkEntry);
                                //linkTable.put(encodeHexString(LinkUtils.linkIdFromLrPacket(packet)), linkEntry);
                            } else {
                                //Entry format is
                                var reserveEntry = ReversEntry.builder()
                                        .receivingInterface(packet.getReceivingInterface())
                                        .outboundInterface(outboundInterface)
                                        .timestamp(Instant.now())
                                        .build();

                                reverseTable.put(encodeHexString(packet.getDestinationHash()), reserveEntry);
                                //reverseTable.put(encodeHexString(packet.getTruncatedHash()), reserveEntry);
                            }

                            transmit(outboundInterface, DataPacketConverter.toBytes(dataPacket));
                            hopsEntry.setTimestamp(Instant.now());
                        } else {
                            // TODO: 11.05.2023 There should probably be some kind of REJECT
                            // mechanism here, to signal to the source that their expected path failed.
                            log.debug(
                                    "Got packet in transport, but no known path to final destination {}. Dropping packet.",
                                    encodeHexString(packet.getDestinationHash())
                            );
                        }
                    }
                }

                //Link transport handling. Directs packets according to entries in the link tables
                if (packet.getPacketType() != ANNOUNCE && packet.getPacketType() != LINKREQUEST && packet.getContext() != LRPROOF) {
                    if (linkTable.containsKey(encodeHexString(packet.getDestinationHash()))) {
                        var linkEntry = linkTable.get(encodeHexString(packet.getDestinationHash()));
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

            // Announce handling. Handles logic related to incoming
            // announces, queueing rebroadcasts of these, and removal
            // of queued announce rebroadcasts once handed to the next node.
            if (packet.getPacketType() == ANNOUNCE) {
                if (nonNull(iface) && validateAnnounce(packet)) {
                    iface.receivedAnnounce();
                }
                if (isFalse(destinationTable.containsKey(encodeHexString(packet.getDestinationHash())))) {
                    // This is an unknown destination, and we'll apply
                    // potential ingress limiting. Already known
                    // destinations will have re-announces controlled
                    // by normal announce rate limiting.
                    if (iface.shouldIngressLimit()) {
                        iface.holdAnnounce(packet);
                        jobsLock.unlock();
                        return;
                    }
                }

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
                        if (owner.isTransportEnabled() && announceTable.containsKey(encodeHexString(packet.getDestinationHash()))) {
                            var announceEntry = announceTable.get(encodeHexString(packet.getDestinationHash()));

                            if (packet.getHops() - 1 == announceEntry.getHops()) {
                                log.debug("Heard a local rebroadcast of announce for {}", encodeHexString(packet.getDestinationHash()));
                                announceEntry.setLocalRebroadcasts(announceEntry.getLocalRebroadcasts() + 1);
                                if (announceEntry.getLocalRebroadcasts() >= LOCAL_REBROADCASTS_MAX) {
                                    log.debug("Max local rebroadcasts of announce for {} reached, dropping announce from our table",
                                            encodeHexString(packet.getDestinationHash()));
                                    announceTable.remove(encodeHexString(packet.getDestinationHash()));
                                }
                            }

                            if (packet.getHops() - 1 == announceEntry.getHops() + 1 && announceEntry.getRetries() > 0) {
                                var now = Instant.now();
                                if (now.isBefore(announceEntry.getRetransmitTimeout())) {
                                    log.debug("Rebroadcasted announce for {} has been passed on to another node, no further tries needed",
                                            encodeHexString(packet.getDestinationHash()));
                                    announceTable.remove(encodeHexString(packet.getDestinationHash()));
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
                        if (destinationTable.containsKey(encodeHexString(packet.getDestinationHash()))) {
                            var hopsEntry = destinationTable.get(encodeHexString(packet.getDestinationHash()));
                            randomBlobs = hopsEntry.getRandomBlobs();

                            //If we already have a path to the announced destination, but the hop count is equal or less, we'll update our tables.
                            if (packet.getHops() <= hopsEntry.getHops()) {
                                //Make sure we haven't heard the random blob before, so announces can't be replayed to forge paths.
                                // TODO: 11.05.2023 Check whether this approach works under all circumstances
                                if (randomBlobs.stream().noneMatch(bytes -> Arrays.equals(bytes, randomBlob))) {
                                    markPathUnknownState(packet.getDestinationHash());
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

                                //If the path has expired, consider this
                                //announce for adding to the path table.
                                if (now.isAfter(pathExpires)) {
                                    // We also check that the announce is
                                    // different from ones we've already heard,
                                    // to avoid loops in the network
                                    if (randomBlobs.stream().noneMatch(bytes -> Arrays.equals(bytes, randomBlob))) {
                                        // TODO: 11.05.2023 Check that this ^ approach actually works under all circumstances
                                        log.debug("Replacing destination table entry for {} with new announce due to expired path",
                                                encodeHexString(packet.getDestinationHash()));
                                        markPathUnknownState(packet.getDestinationHash());
                                        shouldAdd = true;
                                    } else {
                                        shouldAdd = false;
                                    }
                                } else {
                                    // If the path is not expired, but the emission
                                    // is more recent, and we haven't already heard
                                    // this announce before, update the path table.
                                    if (announceEmitted > pathAnnounceEmitted) {
                                        if (randomBlobs.stream().noneMatch(bytes -> Arrays.equals(bytes, randomBlob))) {
                                            log.debug("Replacing destination table entry for {}  with new announce, since it was more recently emitted",
                                                    encodeHexString(packet.getDestinationHash()));
                                            markPathUnknownState(packet.getDestinationHash());
                                            shouldAdd = true;
                                        } else {
                                            shouldAdd = false;
                                        }
                                    }
                                    // If we have already heard this announce before,
                                    // but the path has been marked as unresponsive
                                    // by a failed communications attempt or similar,
                                    // allow updating the path table to this one.
                                    else if (announceEmitted == pathAnnounceEmitted) {
                                        if (pathIsUnresponsive(packet.getDestinationHash())) {
                                            log.debug("Replacing destination table entry for {} with new announce, since previously tried path was unresponsive",
                                                    encodeHexString(packet.getDestinationHash()));
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
                                if (isFalse(announceRateTable.containsKey(encodeHexString(packet.getDestinationHash())))) {
                                    var rateEntryBuilder = RateEntry.builder()
                                            .last(now)
                                            .rateViolations(0)
                                            .blockedUntil(Instant.EPOCH)
                                            .timestamps(new ArrayList<>() {{
                                                add(now);
                                            }});
                                    announceRateTable.put(encodeHexString(packet.getDestinationHash()), rateEntryBuilder.build());
                                } else {
                                    var rateEntry = announceRateTable.get(encodeHexString(packet.getDestinationHash()));
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
                                            encodeHexString(packet.getDestinationHash()));
                                } else {
                                    if (fromLocalClient(packet)) {
                                        //If the announce is from a local client, it is announced immediately, but only one time.
                                        retransmitTimeout = now;
                                        retries = PATHFINDER_R;
                                    }

                                    announceTable.put(
                                            encodeHexString(packet.getDestinationHash()),
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
                            // TODO: 11.05.2023 Check from_local_client once and store result
                            else if (fromLocalClient(packet) && packet.getContext() == PATH_RESPONSE) {
                                //If this is a path response from a local client, check if any external interfaces have pending path requests.
                                if (pendingLocalPathRequests.containsKey(encodeHexString(packet.getDestinationHash()))) {
                                    pendingLocalPathRequests.remove(encodeHexString(packet.getDestinationHash()));
                                    retransmitTimeout = now;
                                    retries = PATHFINDER_R;

                                    announceTable.put(
                                            encodeHexString(packet.getDestinationHash()),
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
                            if (isNotEmpty(localClientInterfaces)) {
                                var announceIdentity = recall(packet.getDestinationHash());
                                var announceDestination = new Destination(announceIdentity, OUT, SINGLE, "unknown", "unknown");
                                announceDestination.setHash(packet.getDestinationHash());
                                announceDestination.setHexHash(encodeHexString(announceDestination.getHash()));
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
                            if (discoveryPathRequests.containsKey(encodeHexString(packet.getDestinationHash()))) {
                                var prEntry = discoveryPathRequests.get(encodeHexString(packet.getDestinationHash()));
                                attachedInterface = prEntry.getRequestingInterface();

                                log.debug("Got matching announce, answering waiting discovery path request for {} on {}",
                                        encodeHexString(packet.getDestinationHash()), attachedInterface.getInterfaceName()
                                );
                                var announceIdentity = recall(packet.getDestinationHash());
                                var announceDestination = new Destination(announceIdentity, OUT, SINGLE, "unknown", "unknown");
                                announceDestination.setHash(packet.getDestinationHash());
                                announceDestination.setHexHash(encodeHexString(announceDestination.getHash()));
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
                                    encodeHexString(packet.getDestinationHash()),
                                    destinationTableEntry
                            );
                            log.debug(
                                    "Destination {} is now {} hops away via {} on {}",
                                    encodeHexString(packet.getDestinationHash()),
                                    announceHops,
                                    encodeHexString(receivedFrom),
                                    packet.getReceivingInterface()
                            );

                            //If the receiving interface is a tunnel, we add the announce to the tunnels table
                            if (
                                    nonNull(packet.getReceivingInterface().getTunnelId())
                                            && tunnels.containsKey(encodeHexString(packet.getReceivingInterface().getTunnelId()))
                            ) {
                                var tunnelEntry = tunnels.get(encodeHexString(packet.getReceivingInterface().getTunnelId()));
                                var paths = tunnelEntry.getTunnelPaths();
                                paths.put(encodeHexString(packet.getDestinationHash()), destinationTableEntry);
                                expires = Instant.now().plusSeconds(DESTINATION_TIMEOUT);
                                tunnelEntry.setExpires(expires);
                                log.debug(
                                        "Path to {} associated with tunnel {}.",
                                        encodeHexString(packet.getDestinationHash()),
                                        encodeHexString(packet.getReceivingInterface().getTunnelId())
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

            //Handling for link requests to local destinations
            else if (packet.getPacketType() == LINKREQUEST) {
                if (isNull(packet.getTransportId()) || Arrays.equals(packet.getTransportId(), identity.getHash())) {
                    for (Destination destination : destinations) {
                        // Note: TODO - implement python path_mtu, mode part
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
                            (owner.isTransportEnabled() || forLocalClientLink || fromLocalClient)
                                    && linkTable.containsKey(encodeHexString(packet.getDestinationHash()))
                    ) {
                        var linkEntry = linkTable.get(encodeHexString(packet.getDestinationHash()));
                        if (packet.getHops() == linkEntry.getRemainingHops()) {
                            if (Objects.equals(packet.getReceivingInterface(), linkEntry.getNextHopInterface())) {
                                try {
                                    //if (getLength(packet.getData()) == (SIGLENGTH / 8 + ECPUBSIZE / 2)) {
                                    if (
                                            (getLength(packet.getData()) == (SIGLENGTH / 8 + ECPUBSIZE / 2)
                                            || getLength(packet.getData()) == SIGLENGTH / 8 + ECPUBSIZE / 2 + LINK_MTU_SIZE)
                                    ) {
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
                                                    encodeHexString(packet.getDestinationHash()));
                                        }
                                    }
                                } catch (Exception e) {
                                    log.error("Error while transporting link request proof.", e);
                                }
                            } else {
                                log.debug("Received link request proof with hop mismatch, not transporting it");
                            }
                        }
                    } else {
                        //Check if we can deliver it to a local pending link
                        for (Link link : pendingLinks) {
                            if (Arrays.equals(link.getLinkId(), packet.getDestinationHash())) {
                                // We need to also allow an expected hops value of
                                // PATHFINDER_M, since in some cases, the number of hops
                                // to the destination will be unknown at link creation
                                // time. The real chance of this occuring is likely to be
                                // extremely small, and this allowance could probably
                                // be discarded without major issues, but it is kept
                                // for now to ensure backwards compatibility.

                                if ((packet.getHops() == link.getExpectedHops()) || (link.getExpectedHops() == TransportConstant.PATHFINDER_M )) {
                                    // Add this packet to the filter hashlist if we
                                    // have determined that it's actually destined
                                    // for this system, and then validate the proof
                                    packetHashMap.put(encodeHexString(packet.getHash()), packet.getHash());
                                    link.validateProof(packet);
                                }
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

                    //Check if this proof needs to be transported
                    if (
                            (owner.isTransportEnabled() || fromLocalClient || proofForLocalClient)
                                    && reverseTable.containsKey(encodeHexString(packet.getDestinationHash()))
                    ) {
                        var reverseEntry = reverseTable.remove(encodeHexString(packet.getDestinationHash()));
                        if (Objects.equals(packet.getReceivingInterface(), reverseEntry.getOutboundInterface())) {
                            log.debug("Proof received on correct interface, transporting it via {}",
                                    reverseEntry.getReceivingInterface().getInterfaceName());
                            var dataPacket = DataPacketConverter.fromBytes(packet.getRaw());
                            dataPacket.getHeader().setHops((byte) packet.getHops());
                            //transmit(reverseEntry.getOutboundInterface(), DataPacketConverter.toBytes(dataPacket));
                            transmit(reverseEntry.getReceivingInterface(), DataPacketConverter.toBytes(dataPacket));
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
                            // In case of an implicit proof, we have
                            // to check every single outstanding receipt
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

    // TODO: 12.05.2023 подлежит рефакторингу. (subject to refactoring)
    public boolean outbound(@NonNull final Packet packet) {
        while (isFalse(jobsLock.tryLock())) {
            //sleep
            try {
                MILLISECONDS.sleep(5);
            } catch (InterruptedException e) {
                log.debug("sleep interrupted: {}", e);
            }
        }

        var sent = false;
        var outboundTime = Instant.now();

        var generateReceipt = packet.isCreateReceipt()
                // Only generate receipts for DATA packets
                && packet.getPacketType() == DATA
                // Don't generate receipts for PLAIN destinations
                && packet.getDestination().getType() != PLAIN
                //Don't generate receipts for link-related packets
                && isFalse(packet.getContext().getValue() >= KEEPALIVE.getValue() && packet.getContext().getValue() <= LRPROOF.getValue())
                // Don't generate receipts for resource packets
                && isFalse(packet.getContext().getValue() >= RESOURCE.getValue() && packet.getContext().getValue() <= RESOURCE_RCL.getValue());

        var packetSent = (Consumer<Packet>) p -> {
            p.setSent(true);
            p.setSentAt(Instant.now());

            if (generateReceipt) {
                p.setReceipt(new PacketReceipt(p));
                receipts.add(p.getReceipt());
            }

            // TODO: Enable when caching has been redesigned
            //cache(packet, false);
        };

        //Check if we have a known path for the destination in the path table
        if (
                packet.getPacketType() != ANNOUNCE
                        && packet.getDestination().getType() != PLAIN
                        && packet.getDestination().getType() != GROUP
                        && destinationTable.containsKey(encodeHexString(packet.getDestinationHash()))
        ) {
            var hopsEntry = destinationTable.get(encodeHexString(packet.getDestinationHash()));
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
                    dataPacket.getAddresses().setHash2(dataPacket.getAddresses().getHash1());
                    dataPacket.getAddresses().setHash1(hopsEntry.getVia());
                    packetSent.accept(packet);
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
                    dataPacket.getAddresses().setHash2(dataPacket.getAddresses().getHash1());
                    dataPacket.getAddresses().setHash1(hopsEntry.getVia());
                    packetSent.accept(packet);
                    transmit(outboundInterface, DataPacketConverter.toBytes(dataPacket));
                    hopsEntry.setTimestamp(outboundTime);
                    sent = true;
                }
            }

            // If none of the above applies, we know the destination is
            // directly reachable, and also on which interface, so we
            // simply transmit the packet directly on that one.
            else {
                packetSent.accept(packet);
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

                                    var queuedAnnounces = isNotEmpty(anInterface.getAnnounceQueue());
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

                                                queuedAnnounces = isNotEmpty(anInterface.getAnnounceQueue());
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
                            packetHashMap.put(encodeHexString(packet.getPacketHash()), packet.getPacketHash());
                            storedHash = true;
                        }

                        // TODO: Re-evaluate potential for blocking
                        // def send_packet():
                        //     Transport.transmit(interface, packet.raw)
                        // thread = threading.Thread(target=send_packet)
                        // thread.daemon = True
                        // thread.start()

                        transmit(anInterface, packet.getRaw());

                        if (packet.getPacketType() == ANNOUNCE) {
                            anInterface.sentAnnounce();
                        }
                        packetSent.accept(packet);
                        sent = true;
                    }
                }
            }
        }

        jobsLock.unlock();

        return sent;
    }

    /**
     * @param destinationHash
     * @return The interface for the next hop to the specified destination, or null if the interface is unknown.
     */
    private ConnectionInterface nextHopInterface(byte[] destinationHash) {
        return Optional.ofNullable(destinationTable.get(encodeHexString(destinationHash)))
                .map(Hops::getInterface)
                .orElse(null);
    }

    private Integer nextHopInterfaceBitrate(byte[] destinationHash) {
        var nextHopInterface = nextHopInterface(destinationHash);

        return nonNull(nextHopInterface) ? nextHopInterface.getBitrate() : null;
    }

    private Integer nextHopPerBitLatency(byte[] destinationHash) {
        var nextHopInterfaceBitrate = nextHopInterfaceBitrate(destinationHash);

        return nonNull(nextHopInterfaceBitrate) ? 1 / nextHopInterfaceBitrate : null;
    }

    private Integer nextHopPerByteLatency(byte[] destinationHash) {
        var perBitLatency = nextHopPerBitLatency(destinationHash);

        return nonNull(perBitLatency) ? perBitLatency * 8 : null;
    }

    /**
     * @param destinationHash
     * @return milliseconds
     */
    public int firstHopTimeout(byte[] destinationHash) {
        var latency = nextHopPerByteLatency(destinationHash);

        return nonNull(latency) ? MTU * latency * 1_000 + DEFAULT_PER_HOP_TIMEOUT : DEFAULT_PER_HOP_TIMEOUT;
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

        // Filter packets intended for other transport instances
        if (nonNull(packet.getTransportId()) && (packet.getPacketType() != ANNOUNCE)) {
            if (isFalse(Arrays.equals(packet.getTransportId(),Transport.getInstance().getIdentity().getHash()))) {
            //if (packet.getTransportId() != Transport.getInstance().getIdentity().getHash()) {
                return false;
            }
        }

        if (Arrays.asList(KEEPALIVE, RESOURCE_REQ, RESOURCE_PRF, RESOURCE, CACHE_REQUEST, CHANNEL).contains(packet.getContext())) {
            return true;
        }

        if (packet.getDestinationType() == PLAIN) {
            if (packet.getPacketType() != ANNOUNCE) {
                if (packet.getHops() > 1) {
                    log.debug("Dropped PLAIN packet {} with {} hops", encodeHexString(packet.getHash()), packet.getHops());
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
                    log.debug("Dropped GROUP packet {} with {} hops", encodeHexString(packet.getHash()), packet.getHops());
                    return false;
                } else {
                    return true;
                }
            } else {
                log.debug("Dropped invalid GROUP announce packet");
                return false;
            }
        }

        if (isFalse(packetHashMap.containsKey(encodeHexString(packet.getPacketHash())))) {
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

        log.trace("Filtered packet with hash {}", encodeHexString(packet.getPacketHash()));

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
            if (isNotEmpty(anInterface.getAnnounceQueue())) {
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
        return Optional.ofNullable(destinationTable.get(encodeHexString(destinationHash)))
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
            if (link.getStatus() != ACTIVE) {
                throw new IllegalStateException("Invalid link state for link activation: " + link.getStatus());
            }
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

    public void cacheRequest(byte[] packetHash, Destination destination) {
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

    public List<AnnounceHandler> getAnnounceHandlers() {
        return announceHandlers;
    }

    public void deregisterAnnounceHandler(AnnounceHandler announceHandler) {
        announceHandlers.remove(announceHandler);
    }

    private void transmit(final ConnectionInterface iface, final byte[] raw) {
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
                var maskedRaw = new byte[newRaw.length];
                for (int i = 0; i < newRaw.length; i++) {
                    if (i == 0) {
                        //Mask first header byte, but make sure the IFAC flag is still set
                        maskedRaw[i] = (byte) (newRaw[i] ^ mask[i] | 0x80);
                    } else if (i == 1 || i > iface.getIfacSize() + 1) {
                        //Mask second header byte and payload
                        maskedRaw[i] = (byte) (newRaw[i] ^ mask[i]);
                    } else {
                        //Don't mask the IFAC itself
                        maskedRaw[i] = newRaw[i];
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
                                encodeHexString(destinationHash), encodeHexString(uniqueTag));
                    }
                } else {
                    log.debug("Ignoring tagless path request for {}.", encodeHexString(destinationHash));
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

        log.debug("Path request for {} on {}", encodeHexString(destinationHash), attachedInterface);

        if (isFalse(localClientInterfaces.isEmpty())) {
            if (destinationTable.containsKey(encodeHexString(destinationHash))) {
                var destinationInterface = destinationTable.get(encodeHexString(destinationHash)).getInterface();

                if (isLocalClientInterface(destinationInterface)) {
                    pendingLocalPathRequests.put(
                            encodeHexString(destinationHash),
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
                    encodeHexString(destinationHash), attachedInterface);
        } else if (
                (getOwner().isTransportEnabled() || isFromLocalClient)
                        && destinationTable.containsKey(encodeHexString(destinationHash))
        ) {
            var destinationEntry = destinationTable.get(encodeHexString(destinationHash));
            //var packet = destinationEntry.getPacket();
            var packet = getCachedPacket(destinationHash, ANNOUNCE);
            var nextHop = destinationEntry.getVia();
            var receivedFrom = destinationEntry.getPacket().getTransportId(); //todo в питоне тут ошибка (?)
            // python version has "Transport.path_table[destination_hash][IDX_PT_RVCD_IF]" (?)
            //var receivedFrom = destinationEntry.getInterface();

            if (isNull(packet)) {
                log.debug("Could not retrieve announce packet from cache while answering path request for {}",
                        encodeHexString(destinationHash));
            } else if (attachedInterface.getMode() == MODE_ROAMING && Objects.equals(attachedInterface, receivedFrom)) {
                log.debug("Not answering path request on roaming-mode interface, since next hop is on same roaming-mode interface");
            } else {
                packet.setHops(destinationEntry.getHops());

                if (nonNull(requestorTransportId) && Arrays.equals(requestorTransportId, nextHop)) {
                    // TODO: Find a bandwidth efficient way to invalidate our
                    // known path on this signal. The obvious way of signing
                    // path requests with transport instance keys is quite
                    // inefficient. There is probably a better way. Doing
                    // path invalidation here would decrease the network
                    // convergence time. Maybe just drop it?
                    log.debug("Not answering path request for {}, since next hop is the requestor", encodeHexString(destinationHash));
                } else {
                    log.debug("Answering path request for {} on {}, path is known",
                            encodeHexString(destinationHash), attachedInterface);

                    var now = Instant.now();
                    var retries = PATHFINDER_R;
                    var localRebroadcasts = 0;
                    var blockRebroadcasts = true;
                    var announceHops = packet.getHops();
                    //var retransmitTimeout = isFromLocalClient ? now : now.plusMillis(PATH_REQUEST_GRACE);
                    var retransmitTimeout = now;

                    if (isFromLocalClient) {
                        retransmitTimeout = now;
                    } else {
                        if (isLocalClientInterface(nextHopInterface(destinationHash))) {
                            log.trace("Path request destination {} is on a local client interface, rebroadcasting immediately");
                            retransmitTimeout = now;
                        } else {
                            retransmitTimeout = now.plusMillis(PATH_REQUEST_GRACE);

                            // If we are answering on a roaming-mode interface, wait a
                            // little longer, to allow potential more well-connected
                            // peers to answer first.
                            if (attachedInterface.getMode() == MODE_ROAMING) {
                                retransmitTimeout = retransmitTimeout.plusMillis(PATH_REQUEST_RG);
                            }
                        }
                    }

                    // This handles an edge case where a peer sends a past
                    // request for a destination just after an announce for
                    // said destination has arrived, but before it has been
                    // rebroadcast locally. In such a case the actual announce
                    // is temporarily held, and then reinserted when the path
                    // request has been served to the peer.
                    if (announceTable.containsKey(encodeHexString(destinationHash))) {
                        var heldEntry = announceTable.get(encodeHexString(destinationHash));
                        heldAnnounces.put(encodeHexString(destinationHash), heldEntry);
                    }

                    announceTable.put(
                            encodeHexString(destinationHash),
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
            }
        } else if (isFromLocalClient) {
            //Forward path request on all interfaces except the local client
            log.debug("Forwarding path request from local client for {} on {} to all other interfaces",
                    encodeHexString(destinationHash), attachedInterface);
            var requestTag = getRandomHash();
            for (ConnectionInterface connectionInterface : interfaces) {
                if (isFalse(Objects.equals(connectionInterface, attachedInterface))) {
                    requestPath(destinationHash, connectionInterface, requestTag, false);
                }
            }
        } else if (shouldSearchForUnknown) {
            if (discoveryPathRequests.containsKey(encodeHexString(destinationHash))) {
                log.debug("There is already a waiting path request for {} on behalf of path request on {}",
                        encodeHexString(destinationHash), attachedInterface);

            } else {
                //Forward path request on all interfaces except the requestor interface
                log.debug("Attempting to discover unknown path to {} on behalf of path request on {}",
                        encodeHexString(destinationHash), attachedInterface);

                discoveryPathRequests.put(encodeHexString(destinationHash),
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
            log.debug("Forwarding path request for {} on {} to local clients", encodeHexString(destinationHash), attachedInterface);
            for (ConnectionInterface connectionInterface : localClientInterfaces) {
                requestPath(destinationHash, connectionInterface, null, false);
            }
        } else {
            log.debug("Ignoring path request for {} on {}, no path known", encodeHexString(destinationHash), attachedInterface);
        }
    }

    /**
     * Check if the path to a destination exists.
     * Note: if not, a call to requestPath(desitinationHash) may be able to retrieve it from the network.
     * 
     * @param destinationHash
     */
    public Boolean hasPath(@NonNull byte[] destinationHash) {
        return destinationTable.containsKey(encodeHexString(destinationHash));
    }

    /**
     * Public version of the method requestPath.
     * 
     * @param destinationHash
     */
    public void requestPath(@NonNull byte[] destinationHash) {
        requestPath(destinationHash, null, null, false);
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
        var packet = new Packet(pathRequestDst, pathRequestData, DATA, BROADCAST, HEADER_1, onInterface);

        if (nonNull(onInterface) && recursive) {
            var queuedAnnounces = isNotEmpty(onInterface.getAnnounceQueue());
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
        pathRequests.put(encodeHexString(destinationHash), Instant.now());
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
        if (isFalse(tunnels.containsKey(encodeHexString(tunnelId)))) {
            log.debug("Tunnel endpoint {} established.", encodeHexString(tunnelId));
            iface.setTunnelId(tunnelId);
            tunnels.put(
                    encodeHexString(tunnelId),
                    Tunnel.builder()
                            .tunnelId(tunnelId)
                            .expires(expires)
                            .tunnelPaths(paths)
                            .anInterface(iface)
                            .build()
            );
        } else {
            log.debug("Tunnel endpoint {} reappeared. Restoring paths...", encodeHexString(tunnelId));
            var tunnelEntry = tunnels.get(encodeHexString(tunnelId));
            tunnelEntry.setAnInterface(iface);
            tunnelEntry.setExpires(expires);
            iface.setTunnelId(tunnelId);
            paths = tunnelEntry.getTunnelPaths();

            var deprecatedPaths = new LinkedList<byte[]>();
            for (Map.Entry<String, Hops> entry : paths.entrySet()) {
                var destinationHash = decodeHex(entry.getKey());
                var pathEntry = entry.getValue();
                var packet = pathEntry.getPacket();
                var announceHops = pathEntry.getHops();
                expires = pathEntry.getExpires();

                var shouldAdd = false;
                if (destinationTable.containsKey(encodeHexString(destinationHash))) {
                    var oldEntry = destinationTable.get(encodeHexString(destinationHash));
                    var oldHops = oldEntry.getHops();
                    var oldExpires = oldEntry.getExpires();
                    if (announceHops < oldHops || Instant.now().isAfter(oldExpires)) {
                        shouldAdd = true;
                    } else {
                        log.debug("Did not restore path to {} because a newer path with fewer hops exist", encodeHexString(packet.getDestinationHash()));
                    }
                } else {
                    if (Instant.now().isBefore(expires)) {
                        shouldAdd = true;
                    } else {
                        log.debug("Did not restore path to {} because it has expired", encodeHexString(packet.getDestinationHash()));
                    }
                }

                if (shouldAdd) {
                    destinationTable.put(
                            encodeHexString(destinationHash),
                            pathEntry.toBuilder()
                                    .timestamp(Instant.now())
                                    .anInterface(iface)
                                    .build()
                    );

                    log.debug(
                            "Restored path to {} is now {} hops away via {}",
                            encodeHexString(packet.getDestinationHash()),
                            announceHops,
                            encodeHexString(pathEntry.getVia())
                    );
                } else {
                    deprecatedPaths.add(destinationHash);
                }
            }

            for (byte[] deprecatedPath : deprecatedPaths) {
                log.debug("Removing path to {} from tunnel {}", encodeHexString(deprecatedPath), encodeHexString(tunnelId));
                paths.remove(encodeHexString(deprecatedPath));
            }
        }
    }

    @SneakyThrows
    private void jobs() {
        List<Packet> outgoing = new LinkedList<>();
        Map<String, ConnectionInterface> pathRequestList = new HashMap<>();
        ConnectionInterface blockedIf = null;

        //if (jobsLock.tryLock()) {
        if (jobsLock.tryLock(3, TimeUnit.SECONDS)) {
            try {
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
                                    if (pathRequests.containsKey(encodeHexString(link.getDestination().getHash()))) {
                                        lastPathRequest = pathRequests.get(encodeHexString(link.getDestination().getHash()));
                                    }

                                    if (Duration.between(lastPathRequest, Instant.now()).toSeconds() > PATH_REQUEST_MI) {
                                        log.debug("Trying to rediscover path for {} since an attempted link was never established",
                                                encodeHexString(link.getDestination().getHash()));
                                        if (pathRequestList.keySet().stream().noneMatch(bytes -> StringUtils.equals(bytes, encodeHexString(link.getDestination().getHash())))) {
                                            blockedIf = null;
                                            pathRequestList.put(encodeHexString(link.getDestination().getHash()), blockedIf);
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
                if (Instant.now().isAfter(receiptsLastChecked.get().plusMillis(RECEIPTS_CHECK_INTERVAL))) {
                    while (receipts.size() > MAX_RECEIPTS) {
                        var culledReceipt = receipts.remove(0);
                        culledReceipt.setTimeout(-1);
                        culledReceipt.checkTimeout();
                    }

                    for (PacketReceipt receipt : receipts) {
                        receipt.checkTimeout();
                        if (receipt.getStatus() != PacketReceiptStatus.SENT) {
                            receipts.remove(receipt);
                        }
                    }

                    receiptsLastChecked.set(Instant.now());
                }

                // Process announces needing retransmission
                if (Instant.now().isAfter(announcesLastChecked.get().plusMillis(ANNOUNCES_CHECK_INTERVAL))) {
                    var completedAnnounces = new HashSet<String>();
                    for (String destinationHashString : announceTable.keySet()) {
                        var announceEntry = announceTable.get(destinationHashString);
                        if (announceEntry.getRetries() > PATHFINDER_R) {
                            log.debug("Completed announce processing for {}, retry limit reached", destinationHashString);
                            completedAnnounces.add(destinationHashString);
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
                                announceDestination.setHexHash(encodeHexString(announceDestination.getHash()));

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
                                    announceTable.put(destinationHashHex, heldAnnounces.remove(destinationHashHex));
                                    log.debug("Reinserting held announce into table");
                                }
                            }
                        }
                    }

                    for (String destinationHash : completedAnnounces) {
                        announceTable.remove(destinationHash);
                    }

                    announcesLastChecked.set(Instant.now());
                }

                //Cull the packet hashlist if it has reached its max size
                if (packetHashMap.size() > HASHLIST_MAXSIZE) {
                    var list = storage.trimPacketHashList();
                    packetHashMap.clear();
                    packetHashMap.putAll(list);
                }

                //Cull the path request tags list if it has reached its max size
                if (discoveryPrTags.size() > MAX_PR_TAGS) {
                    var list = discoveryPrTags.subList(discoveryPrTags.size() - MAX_PR_TAGS, discoveryPrTags.size() - 1);
                    discoveryPrTags.clear();
                    discoveryPrTags.addAll(list);
                }

                if (Instant.now().isAfter(tablesLastCulled.get().plusMillis(TABLES_CULL_INTERVAL))) {
                    //Remove unneeded path state entries
                    var stalePathStates = new LinkedList<String>();
                    for (String hashString : pathStates.keySet()) {
                        if (isFalse(destinationTable.containsKey(hashString))) {
                            stalePathStates.add(hashString);
                        }
                    }
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
                            } else if (isFalse(interfaces.contains(linkEntry.getNextHopInterface()))) {
                                staleLinks.add(linkIdHex);
                            } else if (isFalse(interfaces.contains(linkEntry.getReceivingInterface()))) {
                                staleLinks.add(linkIdHex);
                            }
                        } else {
                            if (Instant.now().isAfter(linkEntry.getProofTimestamp())) {
                                staleLinks.add(linkIdHex);

                                var lastPathRequest = pathRequests.getOrDefault(
                                        encodeHexString(linkEntry.getDestinationHash()),
                                        Instant.EPOCH
                                );

                                var lrTakenHops = linkEntry.getHops();

                                var pathRequestThrottle = Duration.between(lastPathRequest, Instant.now()).toSeconds() < PATH_REQUEST_MI;
                                var pathRequestConditions = false;

                                // If the path has been invalidated between the time of
                                // making the link request and now, try to rediscover it
                                if (isFalse(destinationTable.containsKey(encodeHexString(linkEntry.getDestinationHash())))) {
                                    log.debug("Trying to rediscover path for {} since an attempted link was never established, and path is now missing",
                                            encodeHexString(linkEntry.getDestinationHash()));
                                    pathRequestConditions = true;
                                }

                                // If this link request was originated from a local client
                                // attempt to rediscover a path to the destination, if this
                                // has not already happened recently.
                                else if (isFalse(pathRequestThrottle && lrTakenHops == 0)) {
                                    log.debug("Trying to rediscover path for {} since an attempted local client link was never established",
                                            encodeHexString(linkEntry.getDestinationHash()));
                                    pathRequestConditions = true;
                                }

                                // If the link destination was previously only 1 hop
                                // away, this likely means that it was local to one
                                // of our interfaces, and that it roamed somewhere else.
                                // In that case, try to discover a new path.
                                else if (isFalse(pathRequestThrottle && hopsTo(linkEntry.getDestinationHash()) == 1)) {
                                    log.debug("Trying to rediscover path for {}  since an attempted link was never established, and destination was previously local to an interface on this instance",
                                            encodeHexString(linkEntry.getDestinationHash()));
                                    pathRequestConditions = true;
                                    blockedIf = linkEntry.getReceivingInterface();
                                }

                                // If the link initiator is only 1 hop away,
                                // this likely means that network topology has
                                // changed. In that case, we try to discover a new path,
                                // and mark the old one as potentially unresponsive.
                                else if (isFalse(pathRequestThrottle && lrTakenHops == 1)) {
                                    log.debug("Trying to rediscover path for {} since an attempted link was never established, and link initiator is local to an interface on this instance",
                                            encodeHexString(linkEntry.getDestinationHash()));
                                    pathRequestConditions = true;
                                    blockedIf = linkEntry.getReceivingInterface();

                                    if (getOwner().isTransportEnabled()) {
                                        if (linkEntry.getReceivingInterface().getMode() != MODE_BOUNDARY) {
                                            markPathUnresponsive(linkEntry.getDestinationHash());
                                        }
                                    }
                                }

                                if (pathRequestConditions) {
                                    pathRequestList.putIfAbsent(encodeHexString(linkEntry.getDestinationHash()), blockedIf);

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

                    try {
                        staleReverseEntries.forEach(reverseTable::remove);
                        if (isFalse(staleReverseEntries.isEmpty())) {
                            log.debug("Released {} reverse table entries", staleReverseEntries.size());
                        }
                    } catch (IllegalMonitorStateException e) {
                        log.error("IllegalMonitorStateException while removing staleLinks from reverseTable");
                    }

                    try {
                        staleLinks.forEach(linkTable::remove);
                        if (isFalse(staleLinks.isEmpty())) {
                            log.debug("Released {} links", staleLinks.size());
                        }
                    } catch (IllegalMonitorStateException e) {
                        log.error("IllegalMonitorStateException while removing staleLinks from linkTable");
                    }

                    try {
                        stalePaths.forEach(destinationTable::remove);
                        if (isFalse(stalePaths.isEmpty())) {
                            log.debug("Removed {} waiting path requests", stalePaths.size());
                        }
                    } catch (IllegalMonitorStateException e) {
                        log.error("IllegalMonitorStateException while removing staleLinks from destinationTable");
                    }

                    staleDiscoveryPathRequests.forEach(discoveryPathRequests::remove);
                    if (isFalse(staleDiscoveryPathRequests.isEmpty())) {
                        log.debug("Removed {} waiting path requests", staleDiscoveryPathRequests.size());
                    }

                    staleTunnels.forEach(tunnels::remove);
                    if (isFalse(staleTunnels.isEmpty())) {
                        log.debug("Removed {} tunnels", staleTunnels.size());
                    }

                    var i = 0;
                    for (String destinationHash : stalePathStates) {
                        pathStates.remove(destinationHash);
                        i++;
                    }

                    if (i > 0) {
                        if (i == 1) {
                            log.debug("Removed {} path state entry", i);
                        } else {
                            log.debug("Removed {} path state entries", i);
                        }
                    }

                    tablesLastCulled.set(Instant.now());
                }

                if (Instant.now().isAfter(interfaceLastJobs.get().plusSeconds(interfaceJobsInterval.get().toSeconds()))) {
                    for (ConnectionInterface anInterface : interfaces) {
                        anInterface.processHeldAnnounces();
                    }
                    interfaceLastJobs.set(Instant.now());
                }

            } catch (Exception e) {
                log.error("An exception occurred while running Transport jobs.", e);
            } finally {
                try {
                    jobsLock.unlock();
                } catch (IllegalStateException e) {
                    log.warn("Error while jobsLock unlock", e);
                }
            }
        } else {
            //Transport jobs were locked, do nothing
        }

        outgoing.forEach(Packet::send);
        
        for (String destinationHashString : pathRequestList.keySet()) {
            blockedIf = pathRequestList.get(destinationHashString);
            if (isNull(blockedIf)) {
                requestPath(decodeHex(destinationHashString), null, null, false);
            } else {
                for (ConnectionInterface anInterface : interfaces) {
                    if (isFalse(Objects.equals(anInterface, blockedIf))) {
//                        log.debug("Transmitting path request on {}", anInterface);
                        requestPath(decodeHex(destinationHashString), anInterface, null, false);
                    } else {
//                        log.debug("Blocking path request on {}", anInterface);
                    }
                }
            }
        }
    }

    private boolean markPathResponsive(byte[] destinationHash) {
        if (destinationTable.containsKey(encodeHexString(destinationHash))) {
            pathStates.put(encodeHexString(destinationHash), STATE_RESPONSIVE);
            return true;
        }

        return false;
    }

    private boolean markPathUnknownState(byte[] destinationHash) {
        if (destinationTable.containsKey(encodeHexString(destinationHash))) {
            pathStates.put(encodeHexString(destinationHash), STATE_UNKNOWN);
            return true;
        }

        return false;
    }

    private boolean markPathUnresponsive(byte[] destinationHash) {
        if (destinationTable.containsKey(encodeHexString(destinationHash))) {
            pathStates.put(encodeHexString(destinationHash), STATE_UNRESPONSIVE);
            return true;
        }

        return false;
    }

    private boolean pathIsUnresponsive(byte[] destinationHash) {
        if (pathStates.containsKey(encodeHexString(destinationHash))) {
            return pathStates.get(encodeHexString(destinationHash)) == STATE_UNRESPONSIVE;
        }

        return false;
    }

    /**
     * @param anInterface
     * @return proof timeout in seconds
     */
    private long extraLinkProofTimeout(ConnectionInterface anInterface) {
        if (nonNull(anInterface)) {
            return (1 / anInterface.getBitrate() * 8) * MTU;
        }

        return 0;
    }

    private boolean expirePath(byte[] destinationHash) {
        if (destinationTable.containsKey(encodeHexString(destinationHash))) {
            var entry = destinationTable.get(encodeHexString(destinationHash));
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

        var packet = new Packet(tnlSnthDst, data, DATA, BROADCAST, HEADER_1, iface);
        packet.send();

        iface.setWantsTunnel(false);
    }
}
