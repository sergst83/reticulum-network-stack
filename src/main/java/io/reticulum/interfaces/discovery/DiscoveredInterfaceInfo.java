package io.reticulum.interfaces.discovery;

import lombok.Data;

/**
 * Immutable data object holding information about a remotely discovered interface.
 *
 * <p>Populated by {@link InterfaceAnnounceHandler} when a valid discovery
 * announce is received, and passed to the registered callback.
 *
 * <p>Mirrors the {@code info} dict constructed in Python's
 * {@code InterfaceAnnounceHandler.received_announce()}.
 */
@Data
public class DiscoveredInterfaceInfo {

    /** Interface type string, e.g. {@code "BackboneInterface"}. */
    private String type;

    /** Whether the announcing node has transport enabled. */
    private boolean transport;

    /** Human-readable name advertised by the remote interface. */
    private String name;

    /** Proof-of-work stamp value (number of leading zero bits). */
    private int stampValue;

    /** Hex-encoded transport identity hash of the announcing node. */
    private String transportId;

    /** Hex-encoded network identity hash of the announcing node. */
    private String networkId;

    /** Number of hops to the announcing destination at the time of receipt. */
    private int hops;

    /** GPS latitude advertised by the remote interface. */
    private double latitude;

    /** GPS longitude advertised by the remote interface. */
    private double longitude;

    /** Altitude in metres advertised by the remote interface. */
    private double height;

    /** Epoch-second timestamp at which this announce was received. */
    private long receivedAt;

    /** IP address or hostname to reach the remote interface (TCP types only). */
    private String reachableOn;

    /** TCP port to reach the remote interface (TCP types only). */
    private int port;

    /** IFAC network name, if published by the remote interface. */
    private String ifacNetname;

    /** IFAC passphrase, if published by the remote interface. */
    private String ifacNetkey;

    /**
     * A ready-to-paste Reticulum configuration entry snippet for connecting
     * to this discovered interface.  Only populated for connectable types
     * ({@code BackboneInterface}, {@code TCPServerInterface}).
     */
    private String configEntry;

    /**
     * SHA-256 hash of {@code (transport_id_hex + name)}, used as a stable
     * identifier for deduplication.
     */
    private byte[] discoveryHash;
}