package io.reticulum.interfaces.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reticulum.Transport;
import io.reticulum.identity.Identity;
import io.reticulum.transport.AnnounceHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.msgpack.jackson.dataformat.MessagePackMapper;

import java.util.Map;
import java.util.function.Consumer;

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

/**
 * Handles incoming discovery announces for Reticulum interfaces.
 *
 * <p>Corresponds to {@code InterfaceAnnounceHandler} in the Python reference
 * implementation ({@code RNS/Discovery.py}).
 *
 * <h2>Validation pipeline</h2>
 * <ol>
 *   <li>Verify that the announcing identity is in the configured list of
 *       trusted discovery sources (if any).</li>
 *   <li>Strip the flags byte and, if {@code FLAG_ENCRYPTED}, decrypt the
 *       payload with the transport's network identity.</li>
 *   <li>Separate the msgpack payload from the trailing
 *       {@link LXStamper#STAMP_SIZE}-byte proof-of-work stamp.</li>
 *   <li>Validate the stamp against the configured {@code requiredValue}.</li>
 *   <li>Decode the msgpack payload, build a {@link DiscoveredInterfaceInfo}
 *       record, and pass it to the registered callback.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * InterfaceAnnounceHandler handler = new InterfaceAnnounceHandler(14, info -> {
 *     System.out.println("Discovered: " + info.getConfigEntry());
 * });
 * Transport.getInstance().registerAnnounceHandler(handler);
 * }</pre>
 */
@Slf4j
public class InterfaceAnnounceHandler implements AnnounceHandler {

    /** {@code FLAG_ENCRYPTED} bit in the first byte of the app_data payload. */
    public static final byte FLAG_ENCRYPTED = 0x02;

    @Getter
    private final String aspectFilter = InterfaceAnnouncer.APP_NAME + ".discovery.interface";

    private final int requiredValue;
    private final Consumer<DiscoveredInterfaceInfo> callback;
    private final ObjectMapper msgpack = new MessagePackMapper();

    /**
     * @param requiredValue minimum stamp difficulty to accept (default 14)
     * @param callback      called with parsed info when a valid announce is received;
     *                      may be {@code null}
     */
    public InterfaceAnnounceHandler(int requiredValue, Consumer<DiscoveredInterfaceInfo> callback) {
        this.requiredValue = requiredValue;
        this.callback      = callback;
    }

    public InterfaceAnnounceHandler(Consumer<DiscoveredInterfaceInfo> callback) {
        this(InterfaceAnnouncer.DEFAULT_STAMP_VALUE, callback);
    }

    // ── AnnounceHandler ──────────────────────────────────────────────────────

    @Override
    public void receivedAnnounce(
            byte[] destinationHash,
            Identity announcedIdentity,
            byte[] appData,
            byte[] announcePacketHash,
            boolean isPathResponse
    ) {
        try {
            // Check trusted discovery sources (if configured)
            // Python: if discovery_sources and not announced_identity.hash in discovery_sources: return
            // TODO: wire up Reticulum.interface_discovery_sources() when that API is added

            if (appData == null || appData.length <= LXStamper.STAMP_SIZE + 1) {
                log.debug("Ignoring discovery announce with insufficient app_data length.");
                return;
            }

            byte   flags     = appData[0];
            byte[] payload   = new byte[appData.length - 1];
            System.arraycopy(appData, 1, payload, 0, payload.length);

            boolean encrypted = (flags & FLAG_ENCRYPTED) != 0;

            if (encrypted) {
                var transport = Transport.getInstance();
                var netIdentity = transport.getIdentity();
                if (netIdentity == null) {
                    log.debug("Received encrypted discovery announce but no network identity available; ignoring.");
                    return;
                }
                payload = netIdentity.decrypt(payload);
                if (payload == null) {
                    log.debug("Failed to decrypt discovery announce payload; ignoring.");
                    return;
                }
            }

            if (payload.length <= LXStamper.STAMP_SIZE) {
                log.debug("Discovery announce payload too short after decryption; ignoring.");
                return;
            }

            byte[] stamp  = new byte[LXStamper.STAMP_SIZE];
            byte[] packed = new byte[payload.length - LXStamper.STAMP_SIZE];
            System.arraycopy(payload, payload.length - LXStamper.STAMP_SIZE, stamp,  0, LXStamper.STAMP_SIZE);
            System.arraycopy(payload, 0,                                       packed, 0, packed.length);

            byte[] infohash  = fullHash(packed);
            byte[] workblock = LXStamper.stampWorkblock(infohash);
            int    value     = LXStamper.stampValue(workblock, stamp);

            if (!LXStamper.stampValid(stamp, requiredValue, workblock)) {
                log.debug("Ignored discovered interface with invalid or insufficient stamp (value={}, required={}).",
                        value, requiredValue);
                return;
            }

            // Decode msgpack payload
            Map<Integer, Object> unpacked = msgpack.readValue(packed, new TypeReference<>() {});

            if (!unpacked.containsKey(INTERFACE_TYPE)) {
                log.debug("Discovery announce missing INTERFACE_TYPE field; ignoring.");
                return;
            }

            String interfaceType = String.valueOf(unpacked.get(INTERFACE_TYPE));
            DiscoveredInterfaceInfo info = parseInfo(interfaceType, unpacked, announcedIdentity,
                    destinationHash, value);

            if (info != null && callback != null) {
                callback.accept(info);
            }

        } catch (Exception e) {
            log.debug("An error occurred while decoding discovered interface announce: {}", e.getMessage(), e);
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private DiscoveredInterfaceInfo parseInfo(
            String interfaceType,
            Map<Integer, Object> unpacked,
            Identity announcedIdentity,
            byte[] destinationHash,
            int stampValue
    ) {
        String name = unpacked.containsKey(NAME) ? String.valueOf(unpacked.get(NAME)) : "Discovered " + interfaceType;
        Object transportId = unpacked.get(TRANSPORT_ID);
        String transportIdHex = transportId instanceof byte[]
                ? toHex((byte[]) transportId)
                : String.valueOf(transportId);
        String networkIdHex = toHex(announcedIdentity.getHash());

        var info = new DiscoveredInterfaceInfo();
        info.setType(interfaceType);
        info.setTransport(Boolean.TRUE.equals(unpacked.get(TRANSPORT)));
        info.setName(name);
        info.setStampValue(stampValue);
        info.setTransportId(transportIdHex);
        info.setNetworkId(networkIdHex);
        info.setHops(Transport.getInstance().hopsTo(destinationHash));
        info.setLatitude(toDouble(unpacked.get(LATITUDE)));
        info.setLongitude(toDouble(unpacked.get(LONGITUDE)));
        info.setHeight(toDouble(unpacked.get(HEIGHT)));
        info.setReceivedAt(System.currentTimeMillis() / 1000L);

        if (unpacked.containsKey(IFAC_NETNAME)) info.setIfacNetname(String.valueOf(unpacked.get(IFAC_NETNAME)));
        if (unpacked.containsKey(IFAC_NETKEY))  info.setIfacNetkey(String.valueOf(unpacked.get(IFAC_NETKEY)));

        if ("BackboneInterface".equals(interfaceType) || "TCPServerInterface".equals(interfaceType)) {
            String reachableOn = String.valueOf(unpacked.get(REACHABLE_ON));
            int    port        = toInt(unpacked.get(PORT));
            info.setReachableOn(reachableOn);
            info.setPort(port);

            // Decide connection type: BackboneInterface on Linux/Android, TCPClientInterface otherwise
            boolean isLinux    = System.getProperty("os.name", "").toLowerCase().contains("linux")
                              || System.getProperty("os.name", "").toLowerCase().contains("android");
            String connType    = isLinux ? "BackboneInterface" : "TCPClientInterface";
            String remoteField = isLinux ? "remote"            : "target_host";

            StringBuilder cfg = new StringBuilder();
            cfg.append("[[").append(info.getName()).append("]]\n");
            cfg.append("  type = ").append(connType).append("\n");
            cfg.append("  enabled = yes\n");
            cfg.append("  ").append(remoteField).append(" = ").append(reachableOn).append("\n");
            cfg.append("  target_port = ").append(port).append("\n");
            cfg.append("  transport_identity = ").append(transportIdHex).append("\n");
            if (info.getIfacNetname() != null) cfg.append("  network_name = ").append(info.getIfacNetname()).append("\n");
            if (info.getIfacNetkey()  != null) cfg.append("  passphrase = ").append(info.getIfacNetkey()).append("\n");
            info.setConfigEntry(cfg.toString());
        }

        // Build discovery hash from transport_id + name (matching Python reference)
        byte[] hashMaterial = (transportIdHex + name).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        info.setDiscoveryHash(fullHash(hashMaterial));

        return info;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String toHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0.0; }
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; }
    }
}