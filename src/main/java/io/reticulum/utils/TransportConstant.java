package io.reticulum.utils;

import static io.reticulum.utils.LinkConstant.STALE_TIME;

public class TransportConstant {

    // Constants
    public static final byte BROADCAST = 0x00;
    public static final byte TRANSPORT = 0x01;
    public static final byte RELAY = 0x02;
    public static final byte TUNNEL = 0x03;
    public static final byte[] types = new byte[]{BROADCAST, TRANSPORT, RELAY, TUNNEL};

    public static final byte REACHABILITY_UNREACHABLE = 0x00;
    public static final byte REACHABILITY_DIRECT = 0x01;
    public static final byte REACHABILITY_TRANSPORT = 0x02;

    public static final String APP_NAME = "rnstransport";

    /**
     * Maximum amount of hops that Reticulum will transport a packet.
     */
    public static final int PATHFINDER_M = 128;       // Max hops

    public static final int PATHFINDER_R = 1;          // Retransmit retries
    public static final int PATHFINDER_G = 5;         // Retry grace period
    public static final double PATHFINDER_RW = 0.5;      // Random window for announce rebroadcast
    public static final int PATHFINDER_E = 60 * 60 * 24 * 7; // Path expiration of one week
    public static final int AP_PATH_TIME = 60 * 60 * 24;  // Path expiration of one day for Access Point paths
    public static final int ROAMING_PATH_TIME = 60 * 60 * 6;   // Path expiration of 6 hours for Roaming paths

    // TODO: Calculate an optimal number for this in
    // various situations
    public static final int LOCAL_REBROADCASTS_MAX = 2;        // How many local rebroadcasts of an announce is allowed

    public static final int PATH_REQUEST_TIMEOUT = 15;        // Default timuout for client path requests in seconds
    public static final double PATH_REQUEST_GRACE = 0.35;     // Grace time before a path announcement is made, allows directly reachable peers to respond first
    public static final int PATH_REQUEST_RW = 2;         // Path request random window
    public static final int PATH_REQUEST_MI = 5;       // Minimum interval in seconds for automated path requests

    public static final double LINK_TIMEOUT = STALE_TIME * 1.25;
    public static final int REVERSE_TIMEOUT = 30 * 60;     // Reverse table entries are removed after 30 minutes
    public static final int DESTINATION_TIMEOUT = 60 * 60 * 24 * 7;   // Destination table entries are removed if unused for one week
    public static final int MAX_RECEIPTS = 1024;   // Maximum number of receipts to keep track of
    public static final int MAX_RATE_TIMESTAMPS = 16;  // Maximum number of announce timestamps to keep per destination

}
