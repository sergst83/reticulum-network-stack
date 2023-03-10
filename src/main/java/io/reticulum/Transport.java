package io.reticulum;

import io.reticulum.interfaces.ConnectionInterface;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
    private final Map<?, ?> destinationTable = new ConcurrentHashMap<>();
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
}
