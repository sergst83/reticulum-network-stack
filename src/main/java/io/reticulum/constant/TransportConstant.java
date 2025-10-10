package io.reticulum.constant;

import io.reticulum.interfaces.InterfaceMode;

import java.util.List;

import static io.reticulum.constant.LinkConstant.STALE_TIME;
import static io.reticulum.interfaces.InterfaceMode.MODE_ACCESS_POINT;
import static io.reticulum.interfaces.InterfaceMode.MODE_GATEWAY;
import static io.reticulum.interfaces.InterfaceMode.MODE_ROAMING;

public class TransportConstant {

    public static final List<InterfaceMode> DISCOVER_PATHS_FOR = List.of(MODE_ACCESS_POINT, MODE_GATEWAY, MODE_ROAMING);

    public static final byte REACHABILITY_UNREACHABLE = 0x00;
    public static final byte REACHABILITY_DIRECT = 0x01;
    public static final byte REACHABILITY_TRANSPORT = 0x02;

    public static final String APP_NAME = "rnstransport";

    public static final long JOB_INTERVAL = 250; //ms

    /**
     * Maximum amount of hops that Reticulum will transport a packet.
     */
    public static final int PATHFINDER_M = 128;       // Max hops

    public static final int PATHFINDER_R = 1;          // Retransmit retries
    public static final int PATHFINDER_G = 5;         // Retry grace period
    public static final long PATHFINDER_RW = 500;      // Random window for announce rebroadcast
    public static final int PATHFINDER_E = 60 * 60 * 24 * 7; // Path expiration of one week
    public static final int AP_PATH_TIME = 60 * 60 * 24;  // Path expiration of one day for Access Point paths
    public static final int ROAMING_PATH_TIME = 60 * 60 * 6;   // Path expiration of 6 hours for Roaming paths

    // TODO: Calculate an optimal number for this in
    // various situations
    public static final int LOCAL_REBROADCASTS_MAX = 2;        // How many local rebroadcasts of an announce is allowed

    public static final int PATH_REQUEST_TIMEOUT = 15;        // Default timuout for client path requests in seconds
    /**
     * milliseconds
     */
    public static final long PATH_REQUEST_GRACE = 350;     // Grace time before a path announcement is made, allows directly reachable peers to respond first
    public static final long PATH_REQUEST_RG = 1500;     // Extra grace time [ms] for roaming-ode interfaces to allow more suitable peers to respond first
    public static final int PATH_REQUEST_RW = 2;         // Path request random window
    public static final int PATH_REQUEST_MI = 5;       // Minimum interval in seconds for automated path requests

    public static final long LINK_TIMEOUT = (long) (STALE_TIME * 1.25);
    public static final int REVERSE_TIMEOUT = 30 * 60;     // Reverse table entries are removed after 30 minutes
    public static final int DESTINATION_TIMEOUT = 60 * 60 * 24 * 7;   // Destination table entries are removed if unused for one week
    public static final int MAX_RECEIPTS = 1024;   // Maximum number of receipts to keep track of
    public static final int MAX_RATE_TIMESTAMPS = 16;  // Maximum number of announce timestamps to keep per destination
    public static final int LOCAL_CLIENT_CACHE_MAXSIZE = 512; //
    public static final long LINKS_CHECK_INTERVAL = 1000; //ms
    public static final long RECEIPTS_CHECK_INTERVAL = 1000; //ms
    public static final long ANNOUNCES_CHECK_INTERVAL = 1000; //ms
    public static final int HASHLIST_MAXSIZE = 1000000;
    public static final int MAX_PR_TAGS = 32_000;
    public static final long TABLES_CULL_INTERVAL = 5_000; //ms

}
