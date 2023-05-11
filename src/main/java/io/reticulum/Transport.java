package io.reticulum;

import io.reticulum.destination.Destination;
import io.reticulum.identity.Identity;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import io.reticulum.packet.PacketContextType;
import io.reticulum.packet.data.DataPacket;
import io.reticulum.packet.data.DataPacketConverter;
import io.reticulum.transport.AnnounceEntry;
import io.reticulum.transport.Hops;
import io.reticulum.transport.LinkEntry;
import io.reticulum.transport.PathRequestEntry;
import io.reticulum.transport.RateEntry;
import io.reticulum.transport.ReversEntry;
import io.reticulum.transport.Tunnel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static io.reticulum.constant.IdentityConstant.KEYSIZE;
import static io.reticulum.constant.IdentityConstant.NAME_HASH_LENGTH;
import static io.reticulum.constant.LinkConstant.ESTABLISHMENT_TIMEOUT_PER_HOP;
import static io.reticulum.constant.ReticulumConstant.MTU;
import static io.reticulum.constant.TransportConstant.AP_PATH_TIME;
import static io.reticulum.constant.TransportConstant.LOCAL_CLIENT_CACHE_MAXSIZE;
import static io.reticulum.constant.TransportConstant.LOCAL_REBROADCASTS_MAX;
import static io.reticulum.constant.TransportConstant.MAX_RATE_TIMESTAMPS;
import static io.reticulum.constant.TransportConstant.PATHFINDER_E;
import static io.reticulum.constant.TransportConstant.PATHFINDER_M;
import static io.reticulum.constant.TransportConstant.PATHFINDER_R;
import static io.reticulum.constant.TransportConstant.PATHFINDER_RW;
import static io.reticulum.constant.TransportConstant.ROAMING_PATH_TIME;
import static io.reticulum.destination.DestinationType.PLAIN;
import static io.reticulum.destination.DestinationType.SINGLE;
import static io.reticulum.destination.Direction.IN;
import static io.reticulum.destination.Direction.OUT;
import static io.reticulum.identity.IdentityKnownDestination.recall;
import static io.reticulum.identity.IdentityKnownDestination.validateAnnounce;
import static io.reticulum.interfaces.InterfaceMode.MODE_ACCESS_POINT;
import static io.reticulum.interfaces.InterfaceMode.MODE_ROAMING;
import static io.reticulum.packet.HeaderType.HEADER_1;
import static io.reticulum.packet.HeaderType.HEADER_2;
import static io.reticulum.packet.PacketContextType.CACHE_REQUEST;
import static io.reticulum.packet.PacketContextType.LRPROOF;
import static io.reticulum.packet.PacketContextType.PATH_RESPONSE;
import static io.reticulum.packet.PacketContextType.RESOURCE_PRF;
import static io.reticulum.packet.PacketType.ANNOUNCE;
import static io.reticulum.packet.PacketType.LINKREQUEST;
import static io.reticulum.transport.TransportType.BROADCAST;
import static io.reticulum.transport.TransportType.TRANSPORT;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.ArrayUtils.getLength;
import static org.apache.commons.lang3.ArrayUtils.subarray;
import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.msgpack.value.ValueFactory.newArray;
import static org.msgpack.value.ValueFactory.newBinary;
import static org.msgpack.value.ValueFactory.newInteger;
import static org.msgpack.value.ValueFactory.newString;
import static org.msgpack.value.ValueFactory.newTimestamp;

@Slf4j
@RequiredArgsConstructor
public final class Transport implements ExitHandler {
    private final Lock savingPacketHashlist = new ReentrantLock();
    private final Lock savingPathTable = new ReentrantLock();
    private final Lock savingTunnelTable = new ReentrantLock();
    private final Lock jobsLocked = new ReentrantLock();
    private static volatile Transport INSTANCE;
    private final AtomicBoolean jobsRunning = new AtomicBoolean(false);

    @Getter
    private final Reticulum owner;
    @Getter
    private Identity identity;

    @Getter
    private final List<ConnectionInterface> interfaces = new CopyOnWriteArrayList<>();
    @Getter
    private final List<Destination> destinations = new CopyOnWriteArrayList<>();

    @Getter
    private final List<ConnectionInterface> localClientInterfaces = new CopyOnWriteArrayList<>();

    private final Map<String, AnnounceEntry> announceTable = new ConcurrentHashMap<>();
    private final Map<String, RateEntry> announceRateTable = new ConcurrentHashMap<>();
    private final Map<String, Hops> destinationTable = new ConcurrentHashMap<>();
    private final Map<String, ReversEntry> reverseTable = new ConcurrentHashMap<>();
    private final Map<String, LinkEntry> linkTable = new ConcurrentHashMap<>();
    private final Map<?, ?> heldAnnounces = new ConcurrentHashMap<>();
    private final Map<String, Tunnel> tunnels = new ConcurrentHashMap<>();
    private final List<?> announceHandlers = new CopyOnWriteArrayList<>();
    private final List<Link> activeLinks = new CopyOnWriteArrayList<>();
    private final List<Link> pendingLinks = new CopyOnWriteArrayList<>();
    private final Map<String, ConnectionInterface> pendingLocalPathRequests = new ConcurrentHashMap<>();
    private final Map<String, PathRequestEntry> discoveryPathRequests = new ConcurrentHashMap<>();
    private final List<byte[]> packetHashList = new CopyOnWriteArrayList<>();

    //Transport control destinations are used for control purposes like path requests
    private final List<byte[]> controlHashes = new CopyOnWriteArrayList<>();
    private final List<byte[]> controlDestinations = new CopyOnWriteArrayList<>();

    private final Deque<Pair<byte[], Integer>> localClientRssiCache = new ConcurrentLinkedDeque<>();
    private final Deque<Object> localClientSnrCache = new ConcurrentLinkedDeque<>();

    public static Transport start(@NonNull Reticulum reticulum) {
        Transport transport = INSTANCE;
        if (transport == null) {
            synchronized(Transport.class) {
                transport = INSTANCE;
                if (transport == null) {
                    INSTANCE = transport = new Transport(reticulum);
                }
            }
        }

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
            if (savingPacketHashlist.tryLock(5, TimeUnit.SECONDS)) {
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
            savingPacketHashlist.unlock();
        }
    }

    private void savePathTable() {
        if (owner.isConnectedToSharedInstance()) {
            return;
        }

        try {
            if (savingPathTable.tryLock(5, TimeUnit.SECONDS)) {
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
            savingPathTable.unlock();
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
                String interfaceReference = null;
                if (nonNull(packet.getReceivingInterface())) {
                    interfaceReference = packet.getReceivingInterface().getInterfaceName();
                }

                try (var packer = MessagePack.newDefaultBufferPacker()) {
                    packer.packValue(newArray(
                                    newBinary(packet.getRaw()),
                                    newString(interfaceReference)
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
            if (savingTunnelTable.tryLock(5, TimeUnit.SECONDS)) {
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
            savingTunnelTable.unlock();
        }
    }

    public void inbound(byte[] raw) {
        inbound(raw, null);
    }

    public void inbound(byte[] raw, ConnectionInterface iface) {
        byte[] localRaw = null;
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
            }
        } else {
            return;
        }

        while (jobsRunning.get()) {
            //sleep
        }

        if (isNull(identity)) {
            return;
        }

        jobsLocked.lock();

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

                                var pathAnnounceEmitted = 0;
                                for (byte[] pathRandomBlob : randomBlobs) {
                                    pathAnnounceEmitted = Math.max(
                                            pathAnnounceEmitted,
                                            new BigInteger(subarray(pathRandomBlob, 5, 10)).intValue()
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
                                announceDestination.setHexhash(Hex.encodeHexString(announceDestination.getHash()));
                                var announceContext = PacketContextType.NONE;
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

                                //If we have any waiting discovery path requests for this destination, we retransmit to that
                                // interface immediately
                                if (discoveryPathRequests.containsKey(Hex.encodeHexString(packet.getDestinationHash()))) {
                                    var prEntry = discoveryPathRequests.get(Hex.encodeHexString(packet.getDestinationHash()));
                                    attachedInterface = prEntry.getRequestingInterface();

                                    log.debug("Got matching announce, answering waiting discovery path request for {} on {}",
                                            Hex.encodeHexString(packet.getDestinationHash()), attachedInterface.getInterfaceName()
                                            );
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

                            }
                        }
                    }
                }
            }
        }

        jobsLocked.unlock();
    }

    private boolean fromLocalClient(Packet packet) {
        return false;
    }

    private boolean cacheRequestPacket(Packet packet) {
        return false;
    }

    private void transmit(ConnectionInterface iface, byte[] raw) {

    }

    private boolean packetFilter(Packet packet) {
        return false;
    }

    private boolean interfaceToSharedInstance(ConnectionInterface iface) {
        return false;
    }

    private boolean isLocalClientInterface(ConnectionInterface iface) {
        return false;
    }

    public void sharedConnectionDisappeared() {
        for (Link activeLink : activeLinks) {
            activeLink.teardown();
        }

        for (Link pendingLink: pendingLinks) {
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

    public int announceEmitted(Packet packet) {
        var randomBlob = ArrayUtils.subarray(
                packet.getData(),
                KEYSIZE / 8 + NAME_HASH_LENGTH / 8,
                KEYSIZE / 8  + NAME_HASH_LENGTH / 8 + 10
        );

        return new BigInteger(ArrayUtils.subarray(randomBlob, 5, 10)).intValue();
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

    public void registerLink(Link link) {

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

    public void activateLink(Link link) {

    }

    public boolean outbound(@NonNull final Packet packet) {
        return false;
    }

    public void cacheRequest(byte[] hash, Link link) {

    }
}
