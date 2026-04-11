package io.reticulum.interfaces.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.reticulum.Transport;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.interfaces.backbone.BackboneServerInterface;
import lombok.extern.slf4j.Slf4j;
import org.msgpack.jackson.dataformat.MessagePackMapper;

import java.net.InetAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.reticulum.interfaces.discovery.DiscoveryField.HEIGHT;
import static io.reticulum.interfaces.discovery.DiscoveryField.IFAC_NETKEY;
import static io.reticulum.interfaces.discovery.DiscoveryField.IFAC_NETNAME;
import static io.reticulum.interfaces.discovery.DiscoveryField.INTERFACE_TYPE;
import static io.reticulum.interfaces.discovery.DiscoveryField.LATITUDE;
import static io.reticulum.interfaces.discovery.DiscoveryField.LONGITUDE;
import static io.reticulum.interfaces.discovery.DiscoveryField.NAME;
import static io.reticulum.interfaces.discovery.DiscoveryField.PORT;
import static io.reticulum.interfaces.discovery.DiscoveryField.REACHABLE_ON;
import static io.reticulum.interfaces.discovery.DiscoveryField.TRANSPORT;
import static io.reticulum.interfaces.discovery.DiscoveryField.TRANSPORT_ID;
import static io.reticulum.utils.IdentityUtils.fullHash;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Periodically broadcasts signed, proof-of-work-stamped discovery announces for
 * discoverable {@link BackboneServerInterface} instances.
 *
 * <p>Corresponds to {@code InterfaceAnnouncer} in the Python reference
 * implementation ({@code RNS/Discovery.py}).
 *
 * <h2>Wire format</h2>
 * Each announce's {@code app_data} is:
 * <pre>
 *   flags (1 byte) ‖ payload
 * </pre>
 * where {@code payload = msgpack(info) ‖ stamp} and
 * {@code stamp} is an {@link LXStamper#STAMP_SIZE}-byte proof-of-work nonce.
 * When the {@code FLAG_ENCRYPTED} flag is set the payload is encrypted with the
 * network identity before being appended to the flags byte.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * InterfaceAnnouncer announcer = new InterfaceAnnouncer(transport);
 * announcer.start();
 * }</pre>
 */
@Slf4j
public class InterfaceAnnouncer {

    /** Seconds between each job cycle. */
    public static final int JOB_INTERVAL = 60;

    /** Default proof-of-work difficulty (leading zero bits). */
    public static final int DEFAULT_STAMP_VALUE = 14;

    /** {@code FLAG_ENCRYPTED} bit in the first byte of the app_data payload. */
    public static final byte FLAG_ENCRYPTED = 0x02;

    static final String APP_NAME = "rnstransport";

    private final Transport transport;
    private final Destination discoveryDestination;
    private final ObjectMapper msgpack = new MessagePackMapper();
    private final Map<String, byte[]> stampCache = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public InterfaceAnnouncer(Transport transport) {
        this.transport = transport;

        var identity = transport.getIdentity();
        this.discoveryDestination = new Destination(
                identity, Direction.IN, DestinationType.SINGLE,
                APP_NAME, "discovery", "interface"
        );
    }

    /** Starts the periodic announcement job. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "InterfaceAnnouncer");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::job, JOB_INTERVAL, JOB_INTERVAL, TimeUnit.SECONDS);
            log.debug("InterfaceAnnouncer started.");
        }
    }

    /** Stops the periodic announcement job. */
    public void stop() {
        if (running.compareAndSet(true, false) && nonNull(scheduler)) {
            scheduler.shutdown();
        }
    }

    // ── Periodic job ─────────────────────────────────────────────────────────

    private void job() {
        try {
            long now = Instant.now().getEpochSecond();

            // Find discoverable BackboneServerInterfaces that are due for an announce
            transport.getInterfaces().stream()
                    .filter(i -> i instanceof BackboneServerInterface)
                    .map(i -> (BackboneServerInterface) i)
                    .filter(BackboneServerInterface::isDiscoverable)
                    .filter(i -> now > i.getLastDiscoveryAnnounce() + i.getDiscoveryAnnounceInterval())
                    .max((a, b) -> Long.compare(
                            now - a.getLastDiscoveryAnnounce(),
                            now - b.getLastDiscoveryAnnounce()))
                    .ifPresent(iface -> {
                        iface.setLastDiscoveryAnnounce(now);
                        log.debug("Preparing interface discovery announce for {}", iface.getInterfaceName());
                        byte[] appData = buildAnnounceData(iface);
                        if (appData == null) {
                            log.error("Could not generate interface discovery announce data for {}",
                                    iface.getInterfaceName());
                        } else {
                            log.debug("Sending interface discovery announce for {} ({} bytes payload)",
                                    iface.getInterfaceName(), appData.length);
                            discoveryDestination.announce(appData);
                        }
                    });
        } catch (Exception e) {
            log.error("Error while preparing interface discovery announces: {}", e.getMessage(), e);
        }
    }

    // ── Payload construction ─────────────────────────────────────────────────

    /**
     * Builds the full {@code app_data} bytes for a discovery announce.
     *
     * @param iface the interface to announce
     * @return the serialised payload, or {@code null} on error
     */
    byte[] buildAnnounceData(BackboneServerInterface iface) {
        try {
            String reachableOn = resolveReachableOn(iface);
            if (reachableOn == null) return null;

            Map<Integer, Object> info = new HashMap<>();
            info.put(INTERFACE_TYPE, "BackboneInterface");
            info.put(TRANSPORT,      transport.getOwner().isTransportEnabled());
            info.put(TRANSPORT_ID,   transport.getIdentity().getHash());
            info.put(NAME,           sanitize(iface.getDiscoveryName()));
            info.put(LATITUDE,       iface.getDiscoveryLatitude());
            info.put(LONGITUDE,      iface.getDiscoveryLongitude());
            info.put(HEIGHT,         iface.getDiscoveryHeight());
            info.put(REACHABLE_ON,   reachableOn);
            info.put(PORT,           iface.getListenPort());

            if (iface.isDiscoveryPublishIfac()) {
                if (isNotBlank(iface.getIfacNetName())) info.put(IFAC_NETNAME, iface.getIfacNetName());
                if (isNotBlank(iface.getIfacNetKey()))  info.put(IFAC_NETKEY,  iface.getIfacNetKey());
            }

            byte[] packed   = msgpack.writeValueAsBytes(info);
            byte[] infohash = fullHash(packed);

            // Check stamp cache to avoid regenerating expensive PoW for unchanged info
            String cacheKey = toHex(infohash);
            byte[] stamp = stampCache.get(cacheKey);
            if (stamp == null) {
                int stampCost = iface.getDiscoveryStampValue() > 0
                        ? iface.getDiscoveryStampValue()
                        : DEFAULT_STAMP_VALUE;
                stamp = LXStamper.generateStamp(infohash, stampCost);
                if (stamp == null) return null;
                stampCache.put(cacheKey, stamp);
            }

            byte flags   = 0x00;
            byte[] payload;

            if (iface.isDiscoveryEncrypt()) {
                flags |= FLAG_ENCRYPTED;
                // Encryption requires a network identity; use Transport identity as fallback
                var networkIdentity = transport.getIdentity();
                if (networkIdentity == null) {
                    log.error("Discovery encryption requested for {} but no network identity available. "
                            + "Aborting discovery announce.", iface);
                    return null;
                }
                byte[] plaintext = concat(packed, stamp);
                payload = networkIdentity.encrypt(plaintext);
            } else {
                payload = concat(packed, stamp);
            }

            return concat(new byte[]{flags}, payload);

        } catch (Exception e) {
            log.error("Error building discovery announce data for {}: {}", iface, e.getMessage(), e);
            return null;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Resolves the {@code reachable_on} value.  On Linux this may be an
     * executable path whose stdout provides the address; on all platforms a
     * literal IP/hostname is accepted.
     */
    private String resolveReachableOn(BackboneServerInterface iface) {
        String raw = iface.getReachableOn();
        if (raw == null || raw.isBlank()) {
            log.error("No reachable_on configured for {}, aborting discovery announce.", iface);
            return null;
        }
        String resolved = sanitize(raw);
        if (!isValidAddress(resolved)) {
            log.error("The configured reachable_on \"{}\" for {} is not a valid IP or hostname. "
                    + "Aborting discovery announce.", resolved, iface);
            return null;
        }
        return resolved;
    }

    private static boolean isValidAddress(String s) {
        if (s == null || s.isBlank()) return false;
        // Accept plain hostnames / IP addresses
        // A simple heuristic: try to parse as InetAddress, or accept valid hostname chars
        try {
            InetAddress.getByName(s);
            return true;
        } catch (Exception e) {
            // Allow valid hostname patterns (letters, digits, dots, hyphens)
            return s.matches("[A-Za-z0-9]([A-Za-z0-9\\-\\.]*[A-Za-z0-9])?");
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("\n", "").replace("\r", "").strip();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}