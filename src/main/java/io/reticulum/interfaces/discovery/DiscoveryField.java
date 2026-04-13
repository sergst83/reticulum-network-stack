package io.reticulum.interfaces.discovery;

/**
 * Integer keys for the msgpack-encoded interface discovery announce payload.
 *
 * <p>These constants mirror the Python reference implementation in
 * {@code RNS/Discovery.py}.  The packed payload is a msgpack map whose keys
 * are these single-byte integers; keeping them as bytes rather than strings
 * minimises wire size.
 */
public final class DiscoveryField {

    private DiscoveryField() {}

    /** Human-readable interface name ({@code String}). */
    public static final int NAME            = 0xFF;

    /** Transport identity hash ({@code byte[]}). */
    public static final int TRANSPORT_ID    = 0xFE;

    /** Interface type name, e.g. {@code "BackboneInterface"} ({@code String}). */
    public static final int INTERFACE_TYPE  = 0x00;

    /** Whether the announcing node has transport enabled ({@code boolean}). */
    public static final int TRANSPORT       = 0x01;

    /** IP address or hostname at which the interface is reachable ({@code String}). */
    public static final int REACHABLE_ON    = 0x02;

    /** GPS latitude ({@code double}). */
    public static final int LATITUDE        = 0x03;

    /** GPS longitude ({@code double}). */
    public static final int LONGITUDE       = 0x04;

    /** Altitude in metres ({@code double}). */
    public static final int HEIGHT          = 0x05;

    /** TCP/UDP port number ({@code int}). */
    public static final int PORT            = 0x06;

    /** IFAC network name ({@code String}). */
    public static final int IFAC_NETNAME    = 0x07;

    /** IFAC passphrase ({@code String}). */
    public static final int IFAC_NETKEY     = 0x08;

    /** Radio frequency in Hz ({@code long}). */
    public static final int FREQUENCY       = 0x09;

    /** Radio bandwidth in Hz ({@code long}). */
    public static final int BANDWIDTH       = 0x0A;

    /** LoRa spreading factor ({@code int}). */
    public static final int SPREADINGFACTOR = 0x0B;

    /** LoRa coding rate ({@code int}). */
    public static final int CODINGRATE      = 0x0C;

    /** Radio modulation string ({@code String}). */
    public static final int MODULATION      = 0x0D;

    /** Radio channel number ({@code int}). */
    public static final int CHANNEL         = 0x0E;
}