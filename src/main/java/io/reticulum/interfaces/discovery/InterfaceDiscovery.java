package io.reticulum.interfaces.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reticulum.Transport;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.interfaces.backbone.BackboneClientInterface;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.msgpack.jackson.dataformat.MessagePackMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.util.Objects.nonNull;

/**
 * High-level interface discovery manager.
 *
 * <p>Corresponds to {@code InterfaceDiscovery} in the Python reference
 * implementation ({@code RNS/Discovery.py}).
 *
 * <p>This class:
 * <ul>
 *   <li>Registers an {@link InterfaceAnnounceHandler} with the Transport and
 *       persists discovered interface info to disk.</li>
 *   <li>Exposes {@link #listDiscoveredInterfaces} for querying the persisted
 *       set, including staleness classification.</li>
 *   <li>Optionally auto-connects discovered transport interfaces on startup
 *       and as they are discovered.</li>
 *   <li>Monitors auto-connected interfaces and detaches them if they remain
 *       offline beyond {@link #DETACH_THRESHOLD_SECONDS}.</li>
 * </ul>
 *
 * <h2>Staleness thresholds</h2>
 * <pre>
 *   available : heard within 24 h
 *   unknown   : heard 24 h–3 days ago
 *   stale     : heard 3–7 days ago
 *   (removed) : not heard for ;gt 7 days
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * InterfaceDiscovery discovery = new InterfaceDiscovery(
 *     storagePath, InterfaceAnnouncer.DEFAULT_STAMP_VALUE, info -> {
 *         System.out.println("Discovered: " + info.getConfigEntry());
 *     });
 * }</pre>
 */
@Slf4j
public class InterfaceDiscovery {

    // ── Staleness thresholds (seconds) ────────────────────────────────────────
    public static final long THRESHOLD_UNKNOWN = 24L * 60 * 60;           // 24 h
    public static final long THRESHOLD_STALE   = 3L  * 24 * 60 * 60;     // 3 days
    public static final long THRESHOLD_REMOVE  = 7L  * 24 * 60 * 60;     // 7 days

    /** Polling interval for the monitor job (seconds). */
    public static final int MONITOR_INTERVAL = 5;

    /** Seconds an auto-connected interface may remain offline before being detached. */
    public static final int DETACH_THRESHOLD_SECONDS = 12;

    // ── Status strings ────────────────────────────────────────────────────────
    public static final String STATUS_AVAILABLE = "available";
    public static final String STATUS_UNKNOWN   = "unknown";
    public static final String STATUS_STALE     = "stale";

    // ── Internal field names used in persisted msgpack maps ──────────────────
    private static final String KEY_DISCOVERED  = "discovered";
    private static final String KEY_LAST_HEARD  = "last_heard";
    private static final String KEY_HEARD_COUNT = "heard_count";
    private static final String KEY_STATUS      = "status";
    private static final String KEY_STATUS_CODE = "status_code";
    private static final String KEY_NETWORK_ID  = "network_id";
    private static final String KEY_REACHABLE_ON= "reachable_on";
    private static final String KEY_TYPE        = "type";
    private static final String KEY_TRANSPORT   = "transport";
    private static final String KEY_NAME        = "name";
    private static final String KEY_PORT        = "port";
    private static final String KEY_IFAC_NETNAME= "ifac_netname";
    private static final String KEY_IFAC_NETKEY = "ifac_netkey";
    private static final String KEY_TRANSPORT_ID= "transport_id";
    private static final String KEY_HOPS        = "hops";
    private static final String KEY_LATITUDE    = "latitude";
    private static final String KEY_LONGITUDE   = "longitude";
    private static final String KEY_HEIGHT      = "height";
    private static final String KEY_STAMP_VALUE = "value";
    private static final String KEY_CONFIG_ENTRY= "config_entry";
    private static final String KEY_DISCOVERY_HASH = "discovery_hash";
    private static final String KEY_AUTOCONNECT_HASH = "autoconnect_hash";

    private final Path storagePath;
    private final ObjectMapper msgpack = new MessagePackMapper();
    private final Consumer<DiscoveredInterfaceInfo> externalCallback;
    private final InterfaceAnnounceHandler handler;

    private final List<ConnectionInterface> monitoredInterfaces = new CopyOnWriteArrayList<>();
    private volatile boolean monitoring = false;
    private volatile boolean initialAutoconnectRan = false;
    private ScheduledExecutorService monitorExecutor;

    /**
     * Creates an InterfaceDiscovery manager.
     *
     * @param storageBase        base storage path (the manager creates a {@code discovery/interfaces}
     *                           sub-directory)
     * @param requiredStampValue minimum PoW difficulty to accept (use
     *                           {@link InterfaceAnnouncer#DEFAULT_STAMP_VALUE} for the default 14)
     * @param callback           called whenever a valid new or updated interface is discovered;
     *                           may be {@code null}
     */
    public InterfaceDiscovery(Path storageBase, int requiredStampValue,
                              Consumer<DiscoveredInterfaceInfo> callback) {
        this.storagePath      = storageBase.resolve("discovery").resolve("interfaces");
        this.externalCallback = callback;

        try {
            Files.createDirectories(storagePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create discovery storage directory: " + storagePath, e);
        }

        // Register announce handler with Transport
        this.handler = new InterfaceAnnounceHandler(requiredStampValue, this::interfaceDiscovered);
        Transport.getInstance().registerAnnounceHandler(handler);

        // Reconnect previously discovered transport interfaces in the background
        Thread connectThread = new Thread(this::connectDiscovered, "InterfaceDiscovery-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    /** Convenience constructor with default stamp value and no external callback. */
    public InterfaceDiscovery(Path storageBase) {
        this(storageBase, InterfaceAnnouncer.DEFAULT_STAMP_VALUE, null);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns persisted discovered interfaces, optionally filtered.
     *
     * @param onlyAvailable if {@code true}, only return interfaces heard within 24 h
     * @param onlyTransport if {@code true}, only return interfaces from transport nodes
     * @return list of info maps, sorted by (status, stamp_value, last_heard) descending
     */
    public List<Map<String, Object>> listDiscoveredInterfaces(boolean onlyAvailable, boolean onlyTransport) {
        long now = System.currentTimeMillis() / 1000L;
        List<Map<String, Object>> result = new ArrayList<>();

        File dir = storagePath.toFile();
        if (!dir.isDirectory()) return result;

        for (File file : dir.listFiles()) {
            try {
                byte[] data = Files.readAllBytes(file.toPath());
                Map<String, Object> info = msgpack.readValue(data, new TypeReference<>() {});

                long lastHeard = toLong(info.get(KEY_LAST_HEARD));
                long heardDelta = now - lastHeard;

                // Remove entries that are too old or have invalid data
                if (heardDelta > THRESHOLD_REMOVE) {
                    Files.deleteIfExists(file.toPath());
                    continue;
                }

                Object reachableOn = info.get(KEY_REACHABLE_ON);
                if (reachableOn != null && !isValidAddress(String.valueOf(reachableOn))) {
                    Files.deleteIfExists(file.toPath());
                    continue;
                }

                // Classify staleness
                String status;
                int statusCode;
                if (heardDelta > THRESHOLD_STALE) {
                    status = STATUS_STALE;
                    statusCode = 0;
                } else if (heardDelta > THRESHOLD_UNKNOWN) {
                    status = STATUS_UNKNOWN;
                    statusCode = 100;
                } else {
                    status = STATUS_AVAILABLE;
                    statusCode = 1000;
                }
                info.put(KEY_STATUS, status);
                info.put(KEY_STATUS_CODE, statusCode);

                if (onlyAvailable && !STATUS_AVAILABLE.equals(status)) continue;
                if (onlyTransport && !Boolean.TRUE.equals(info.get(KEY_TRANSPORT))) continue;

                result.add(info);

            } catch (Exception e) {
                log.error("Error while loading discovered interface data from {}: {}", file, e.getMessage());
            }
        }

        // Sort: status_code desc, stamp_value desc, last_heard desc
        result.sort((a, b) -> {
            int cmp = Integer.compare(toInt(b.get(KEY_STATUS_CODE)), toInt(a.get(KEY_STATUS_CODE)));
            if (cmp != 0) return cmp;
            cmp = Integer.compare(toInt(b.get(KEY_STAMP_VALUE)), toInt(a.get(KEY_STAMP_VALUE)));
            if (cmp != 0) return cmp;
            return Long.compare(toLong(b.get(KEY_LAST_HEARD)), toLong(a.get(KEY_LAST_HEARD)));
        });

        return result;
    }

    /** Returns all discovered interfaces regardless of availability or transport status. */
    public List<Map<String, Object>> listDiscoveredInterfaces() {
        return listDiscoveredInterfaces(false, false);
    }

    /** Stops the monitor job and deregisters the announce handler. */
    public void stop() {
        monitoring = false;
        if (nonNull(monitorExecutor)) {
            monitorExecutor.shutdown();
        }
        Transport.getInstance().deregisterAnnounceHandler(handler);
    }

    // ── Discovery callback ────────────────────────────────────────────────────

    private void interfaceDiscovered(DiscoveredInterfaceInfo info) {
        try {
            String name           = info.getName();
            String interfaceType  = info.getType();
            String discoveryHashHex = Hex.encodeHexString(info.getDiscoveryHash());
            int    hops            = info.getHops();
            String hopStr          = (hops == 1) ? "hop" : "hops";

            log.debug("Discovered {} {} {} away with stamp value {}: {}",
                    interfaceType, hops, hopStr, info.getStampValue(), name);

            Path filePath = storagePath.resolve(discoveryHashHex);
            long now = System.currentTimeMillis() / 1000L;

            Map<String, Object> stored = new HashMap<>();
            stored.put(KEY_TYPE,         info.getType());
            stored.put(KEY_TRANSPORT,    info.isTransport());
            stored.put(KEY_NAME,         info.getName());
            stored.put(KEY_STAMP_VALUE,  info.getStampValue());
            stored.put(KEY_TRANSPORT_ID, info.getTransportId());
            stored.put(KEY_NETWORK_ID,   info.getNetworkId());
            stored.put(KEY_HOPS,         info.getHops());
            stored.put(KEY_LATITUDE,     info.getLatitude());
            stored.put(KEY_LONGITUDE,    info.getLongitude());
            stored.put(KEY_HEIGHT,       info.getHeight());
            if (info.getReachableOn() != null)  stored.put(KEY_REACHABLE_ON, info.getReachableOn());
            if (info.getPort() != 0)            stored.put(KEY_PORT, info.getPort());
            if (info.getIfacNetname() != null)  stored.put(KEY_IFAC_NETNAME, info.getIfacNetname());
            if (info.getIfacNetkey() != null)   stored.put(KEY_IFAC_NETKEY,  info.getIfacNetkey());
            if (info.getConfigEntry() != null)  stored.put(KEY_CONFIG_ENTRY, info.getConfigEntry());
            if (info.getDiscoveryHash() != null) stored.put(KEY_DISCOVERY_HASH, info.getDiscoveryHash());

            if (!Files.exists(filePath)) {
                stored.put(KEY_DISCOVERED,  now);
                stored.put(KEY_LAST_HEARD,  now);
                stored.put(KEY_HEARD_COUNT, 0);
                writeInfoFile(filePath, stored);
            } else {
                // Update last_heard and increment heard_count; preserve original discovered timestamp
                try {
                    byte[] existing = Files.readAllBytes(filePath);
                    Map<String, Object> prev = msgpack.readValue(existing, new TypeReference<>() {});
                    stored.put(KEY_DISCOVERED,  prev.getOrDefault(KEY_DISCOVERED, now));
                    stored.put(KEY_LAST_HEARD,  now);
                    stored.put(KEY_HEARD_COUNT, toLong(prev.get(KEY_HEARD_COUNT)) + 1);
                } catch (Exception e) {
                    stored.put(KEY_DISCOVERED,  now);
                    stored.put(KEY_LAST_HEARD,  now);
                    stored.put(KEY_HEARD_COUNT, 0);
                }
                writeInfoFile(filePath, stored);
            }

            autoconnect(info);

            if (externalCallback != null) {
                externalCallback.accept(info);
            }

        } catch (Exception e) {
            log.error("Error processing discovered interface data: {}", e.getMessage(), e);
        }
    }

    // ── Auto-connect ──────────────────────────────────────────────────────────

    /** Attempts to reconnect previously discovered transport interfaces from disk. */
    private void connectDiscovered() {
        try {
            List<Map<String, Object>> discovered = listDiscoveredInterfaces(false, true);
            int maxConn = maxAutoconnectedInterfaces();

            for (Map<String, Object> info : discovered) {
                if (autoconnectCount() >= maxConn) break;
                autoconnectFromMap(info);
            }
        } catch (Exception e) {
            log.error("Error while reconnecting discovered interfaces: {}", e.getMessage(), e);
        } finally {
            initialAutoconnectRan = true;
        }
    }

    /** Attempts to auto-connect a newly discovered interface. */
    private void autoconnect(DiscoveredInterfaceInfo info) {
        if (!shouldAutoconnect()) return;
        if (autoconnectCount() >= maxAutoconnectedInterfaces()) return;

        String interfaceType = info.getType();
        if (!"BackboneInterface".equals(interfaceType) && !"TCPServerInterface".equals(interfaceType)) return;

        String reachableOn = info.getReachableOn();
        int    port        = info.getPort();
        if (reachableOn == null) return;

        if (interfaceExists(reachableOn, port)) {
            log.debug("Discovered {} already exists, not auto-connecting", interfaceType);
            return;
        }

        String name = info.getName();
        log.info("Auto-connecting discovered {} {}", interfaceType, name);

        try {
            var iface = new BackboneClientInterface();
            iface.setInterfaceName(name);
            iface.setTargetHost(reachableOn);
            iface.setTargetPort(port);
            iface.setEnabled(true);
            byte[] endpointHash = endpointHash(reachableOn, port);
            iface.setAutoconnectHash(endpointHash);
            iface.setAutoconnectSource(info.getNetworkId());

            Transport.getInstance().getInterfaces().add(iface);
            iface.launch();

            monitorInterface(iface);
            log.info("Auto-connected to discovered {} at {}:{}", interfaceType, reachableOn, port);

        } catch (Exception e) {
            log.error("Error while auto-connecting to discovered {}: {}", interfaceType, e.getMessage(), e);
        }
    }

    /** Auto-connects from a persisted info map (used at startup). */
    private void autoconnectFromMap(Map<String, Object> info) {
        try {
            String type       = String.valueOf(info.get(KEY_TYPE));
            String reachableOn = info.containsKey(KEY_REACHABLE_ON) ? String.valueOf(info.get(KEY_REACHABLE_ON)) : null;
            int    port        = toInt(info.get(KEY_PORT));
            String name        = info.containsKey(KEY_NAME) ? String.valueOf(info.get(KEY_NAME)) : "Discovered";

            if (!"BackboneInterface".equals(type) && !"TCPServerInterface".equals(type)) return;
            if (reachableOn == null) return;
            if (interfaceExists(reachableOn, port)) return;

            // Synthesize a DiscoveredInterfaceInfo and delegate
            var synthetic = new DiscoveredInterfaceInfo();
            synthetic.setType(type);
            synthetic.setName(name);
            synthetic.setReachableOn(reachableOn);
            synthetic.setPort(port);
            synthetic.setTransport(Boolean.TRUE.equals(info.get(KEY_TRANSPORT)));
            synthetic.setNetworkId(info.containsKey(KEY_NETWORK_ID) ? String.valueOf(info.get(KEY_NETWORK_ID)) : "");
            if (info.containsKey(KEY_IFAC_NETNAME)) synthetic.setIfacNetname(String.valueOf(info.get(KEY_IFAC_NETNAME)));
            if (info.containsKey(KEY_IFAC_NETKEY))  synthetic.setIfacNetkey(String.valueOf(info.get(KEY_IFAC_NETKEY)));
            autoconnect(synthetic);

        } catch (Exception e) {
            log.error("Error while auto-connecting from persisted discovery entry: {}", e.getMessage(), e);
        }
    }

    // ── Interface monitor ─────────────────────────────────────────────────────

    private void monitorInterface(ConnectionInterface iface) {
        if (!monitoredInterfaces.contains(iface)) {
            monitoredInterfaces.add(iface);
        }
        startMonitor();
    }

    private synchronized void startMonitor() {
        if (monitoring) return;
        monitoring = true;
        monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "InterfaceDiscovery-monitor");
            t.setDaemon(true);
            return t;
        });
        monitorExecutor.scheduleAtFixedRate(this::monitorJob, MONITOR_INTERVAL, MONITOR_INTERVAL, TimeUnit.SECONDS);
    }

    private void monitorJob() {
        List<ConnectionInterface> toDetach = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;

        for (ConnectionInterface iface : monitoredInterfaces) {
            try {
                if (iface.isOnline()) {
                    // Reset down-since marker on reconnect
                    iface.setAutoconnectDown(null);
                } else {
                    Long downSince = iface.getAutoconnectDown();
                    if (downSince == null) {
                        log.debug("Auto-discovered interface {} disconnected", iface.getInterfaceName());
                        iface.setAutoconnectDown(now);
                    } else {
                        long downFor = now - downSince;
                        if (downFor >= DETACH_THRESHOLD_SECONDS) {
                            log.debug("Auto-discovered interface {} has been down for {}s, detaching",
                                    iface.getInterfaceName(), downFor);
                            toDetach.add(iface);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error while checking auto-connected interface state for {}: {}",
                        iface.getInterfaceName(), e.getMessage());
            }
        }

        for (ConnectionInterface iface : toDetach) {
            teardownInterface(iface);
        }

        // If no monitored interfaces are online, try auto-connecting more
        if (initialAutoconnectRan && shouldAutoconnect()) {
            long onlineCount = monitoredInterfaces.stream()
                    .filter(ConnectionInterface::isOnline).count();
            long free = maxAutoconnectedInterfaces() - autoconnectCount();
            long reserved = maxAutoconnectedInterfaces() / 4;

            if (onlineCount > 0 && free > reserved) {
                List<Map<String, Object>> candidates = listDiscoveredInterfaces(true, true);
                for (Map<String, Object> candidate : candidates) {
                    String reachable = candidate.containsKey(KEY_REACHABLE_ON)
                            ? String.valueOf(candidate.get(KEY_REACHABLE_ON)) : null;
                    int    port      = toInt(candidate.get(KEY_PORT));
                    if (reachable != null && !interfaceExists(reachable, port)) {
                        autoconnectFromMap(candidate);
                        break;
                    }
                }
            }
        }
    }

    private void teardownInterface(ConnectionInterface iface) {
        try {
            iface.detach();
        } catch (Exception ignored) {}
        Transport.getInstance().getInterfaces().remove(iface);
        monitoredInterfaces.remove(iface);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean interfaceExists(String reachableOn, int port) {
        byte[] hash = endpointHash(reachableOn, port);
        for (ConnectionInterface iface : Transport.getInstance().getInterfaces()) {
            byte[] ifaceHash = iface.getAutoconnectHash();
            if (ifaceHash != null && java.util.Arrays.equals(ifaceHash, hash)) return true;
            // Also check by target host/port for non-autoconnected matching interfaces
            if (reachableOn.equals(iface.getTargetHost()) && port == iface.getTargetPort()) return true;
        }
        return false;
    }

    private byte[] endpointHash(String reachableOn, int port) {
        String specifier = reachableOn + ":" + port;
        return io.reticulum.utils.IdentityUtils.fullHash(
                specifier.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private int autoconnectCount() {
        return (int) Transport.getInstance().getInterfaces().stream()
                .filter(i -> i.getAutoconnectHash() != null)
                .count();
    }

    private boolean shouldAutoconnect() {
        // Default: auto-connect is enabled unless explicitly disabled in config
        // Python checks RNS.Reticulum.should_autoconnect_discovered_interfaces()
        return true;
    }

    private int maxAutoconnectedInterfaces() {
        // Python default is 4 in Reticulum config; use same default
        return 4;
    }

    private void writeInfoFile(Path path, Map<String, Object> info) {
        try {
            Path tmp = path.getParent().resolve(path.getFileName() + ".tmp");
            Files.write(tmp, msgpack.writeValueAsBytes(info));
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Error while persisting discovered interface data to {}: {}", path, e.getMessage());
        }
    }

    private static boolean isValidAddress(String s) {
        if (s == null || s.isBlank()) return false;
        try {
            java.net.InetAddress.getByName(s);
            return true;
        } catch (Exception e) {
            return s.matches("[A-Za-z0-9]([A-Za-z0-9\\-\\.]*[A-Za-z0-9])?");
        }
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0L; }
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; }
    }
}
