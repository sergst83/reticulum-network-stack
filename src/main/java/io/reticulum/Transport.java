package io.reticulum;

import io.reticulum.destination.Destination;
import io.reticulum.identity.Identity;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import io.reticulum.transport.Hops;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static io.reticulum.constant.IdentityConstant.KEYSIZE;
import static io.reticulum.constant.IdentityConstant.NAME_HASH_LENGTH;
import static io.reticulum.constant.ReticulumConstant.MTU;
import static io.reticulum.constant.TransportConstant.PATHFINDER_M;
import static io.reticulum.destination.DestinationType.SINGLE;
import static io.reticulum.destination.Direction.IN;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.nonNull;
import static java.util.stream.Collectors.toList;
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
    private static volatile Transport INSTANCE;

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

    private final Map<?, ?> announceTable = new ConcurrentHashMap<>();
    private final Map<String, Hops> destinationTable = new ConcurrentHashMap<>();
    private final Map<?, ?> reverseTable = new ConcurrentHashMap<>();
    private final Map<?, ?> linkTable = new ConcurrentHashMap<>();
    private final Map<?, ?> heldAnnounces = new ConcurrentHashMap<>();
    private final Map<?, ?> tunnels = new ConcurrentHashMap<>();
    private final List<?> announceHandlers = new CopyOnWriteArrayList<>();
    private final List<Link> activeLinks = new CopyOnWriteArrayList<>();
    private final List<Link> pendingLinks = new CopyOnWriteArrayList<>();
    private final List<byte[]> packetHashList = new CopyOnWriteArrayList<>();

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
                                        newBinary(de.getRandomBlobs()),
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
        return false;
    }

    private ConnectionInterface findInterfaceFromHash(byte[] interfaceHash) {
        return null;
    }

    private void saveTunnelTable() {

    }

    public void inbound(byte[] data) {

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
