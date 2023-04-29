package io.reticulum;

import io.reticulum.destination.Destination;
import io.reticulum.identity.Identity;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import io.reticulum.transport.Hop;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.binary.Hex;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.reticulum.constant.ReticulumConstant.MTU;
import static io.reticulum.constant.TransportConstant.PATHFINDER_M;
import static io.reticulum.destination.DestinationType.SINGLE;
import static io.reticulum.destination.Direction.IN;

@RequiredArgsConstructor
public final class Transport implements ExitHandler {
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
    private final Map<String, Hop> destinationTable = new ConcurrentHashMap<>();
    private final Map<?, ?> reverseTable = new ConcurrentHashMap<>();
    private final Map<?, ?> linkTable = new ConcurrentHashMap<>();
    private final Map<?, ?> heldAnnounces = new ConcurrentHashMap<>();
    private final Map<?, ?> tunnels = new ConcurrentHashMap<>();
    private final List<?> announceHandlers = new CopyOnWriteArrayList<>();
    private final List<Link> activeLinks = new CopyOnWriteArrayList<>();
    private final List<Link> pendingLinks = new CopyOnWriteArrayList<>();

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

    }

    public void persistData() {
        savePacketHashlist();
        savePathTable();
        saveTunnelTable();
    }

    private void savePacketHashlist() {

    }

    private void savePathTable() {

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
                .map(Hop::getPathLength)
                .orElse(PATHFINDER_M);
    }

    public void activateLink(Link link) {

    }

    public boolean outbound(@NonNull final Packet packet) {
        return false;
    }
}
