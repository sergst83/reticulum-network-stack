package io.reticulum;

import io.reticulum.interfaces.discovery.InterfaceAnnouncer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.reticulum.constant.IdentityConstant.DERIVED_KEY_LENGTH;
import static io.reticulum.constant.IdentityConstant.DERIVED_KEY_LENGTH_LEGACY;
import static io.reticulum.constant.IdentityConstant.NAME_HASH_LENGTH;
import static io.reticulum.constant.IdentityConstant.RATCHETSIZE;
import static io.reticulum.constant.IdentityConstant.RATCHET_EXPIRY;
import static io.reticulum.constant.IdentityConstant.TOKEN_OVERHEAD;
import static io.reticulum.constant.LinkConstant.ECPUBSIZE;
import static io.reticulum.constant.LinkConstant.KEEPALIVE;
import static io.reticulum.constant.LinkConstant.KEEPALIVE_TIMEOUT_FACTOR;
import static io.reticulum.constant.LinkConstant.LINK_MTU_SIZE;
import static io.reticulum.constant.LinkConstant.MODE_DEFAULT;
import static io.reticulum.constant.LinkConstant.MODE_AES256_CBC;
import static io.reticulum.constant.LinkConstant.MTU_BYTEMASK;
import static io.reticulum.constant.LinkConstant.STALE_FACTOR;
import static io.reticulum.constant.LinkConstant.STALE_GRACE;
import static io.reticulum.constant.LinkConstant.STALE_TIME;
import static io.reticulum.constant.LinkConstant.TRAFFIC_TIMEOUT_FACTOR;
import static io.reticulum.constant.ReticulumConstant.ANNOUNCE_CAP;
import static io.reticulum.constant.ReticulumConstant.CLEAN_INTERVAL;
import static io.reticulum.constant.ReticulumConstant.GRACIOUS_PERSIST_INTERVAL;
import static io.reticulum.constant.ReticulumConstant.HEADER_MAXSIZE;
import static io.reticulum.constant.ReticulumConstant.HEADER_MINSIZE;
import static io.reticulum.constant.ReticulumConstant.MAX_QUEUED_ANNOUNCES;
import static io.reticulum.constant.ReticulumConstant.MDU;
import static io.reticulum.constant.ReticulumConstant.MINIMUM_BITRATE;
import static io.reticulum.constant.ReticulumConstant.MTU;
import static io.reticulum.constant.ReticulumConstant.PERSIST_INTERVAL;
import static io.reticulum.constant.ReticulumConstant.QUEUED_ANNOUNCE_LIFE;
import static io.reticulum.constant.ReticulumConstant.RESOURCE_CACHE;
import static io.reticulum.constant.ReticulumConstant.TRUNCATED_HASHLENGTH;
import static io.reticulum.constant.TransportConstant.AP_PATH_TIME;
import static io.reticulum.constant.TransportConstant.DESTINATION_TIMEOUT;
import static io.reticulum.constant.TransportConstant.LINK_TIMEOUT;
import static io.reticulum.constant.TransportConstant.LOCAL_CLIENT_CACHE_MAXSIZE;
import static io.reticulum.constant.TransportConstant.LOCAL_REBROADCASTS_MAX;
import static io.reticulum.constant.TransportConstant.MAX_RANDOM_BLOBS;
import static io.reticulum.constant.TransportConstant.MAX_RATE_TIMESTAMPS;
import static io.reticulum.constant.TransportConstant.MAX_RECEIPTS;
import static io.reticulum.constant.TransportConstant.PATHFINDER_E;
import static io.reticulum.constant.TransportConstant.PATHFINDER_G;
import static io.reticulum.constant.TransportConstant.PATHFINDER_M;
import static io.reticulum.constant.TransportConstant.PATHFINDER_R;
import static io.reticulum.constant.TransportConstant.PATHFINDER_RW;
import static io.reticulum.constant.TransportConstant.PATH_REQUEST_GRACE;
import static io.reticulum.constant.TransportConstant.PATH_REQUEST_MI;
import static io.reticulum.constant.TransportConstant.PATH_REQUEST_RG;
import static io.reticulum.constant.TransportConstant.PATH_REQUEST_TIMEOUT;
import static io.reticulum.constant.TransportConstant.REVERSE_TIMEOUT;
import static io.reticulum.constant.TransportConstant.ROAMING_PATH_TIME;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the protocol constants shared with Python RNS 1.5.2 to the reference
 * values, so drift shows up as a test failure rather than as subtly divergent
 * behaviour on a live network.
 * <p>
 * Where this implementation deliberately works in milliseconds and the
 * reference in seconds, the assertion states the conversion explicitly rather
 * than asserting the raw reference number.
 */
class ConstantParityTest {

    @Test
    @DisplayName("Reticulum constants match RNS/Reticulum.py")
    void reticulumConstants() {
        assertEquals(500, MTU);
        assertEquals(464, MDU);
        assertEquals(19, HEADER_MINSIZE);
        assertEquals(35, HEADER_MAXSIZE);
        assertEquals(128, TRUNCATED_HASHLENGTH);
        assertEquals(2, ANNOUNCE_CAP);
        assertEquals(5, MINIMUM_BITRATE);
        assertEquals(4096, MAX_QUEUED_ANNOUNCES);
        assertEquals(10800, QUEUED_ANNOUNCE_LIFE, "3 hours");
        assertEquals(86400, RESOURCE_CACHE);
        assertEquals(900, CLEAN_INTERVAL);
        assertEquals(43200, PERSIST_INTERVAL);
        assertEquals(300, GRACIOUS_PERSIST_INTERVAL);
    }

    @Test
    @DisplayName("Transport constants match RNS/Transport.py")
    void transportConstants() {
        assertEquals(128, PATHFINDER_M);
        assertEquals(1, PATHFINDER_R);
        assertEquals(5, PATHFINDER_G);
        assertEquals(604800, PATHFINDER_E);
        assertEquals(86400, AP_PATH_TIME);
        assertEquals(21600, ROAMING_PATH_TIME);
        assertEquals(2, LOCAL_REBROADCASTS_MAX);
        assertEquals(15, PATH_REQUEST_TIMEOUT);
        assertEquals(20, PATH_REQUEST_MI, "was 5 — Java requested paths 4x too often");
        assertEquals(480, REVERSE_TIMEOUT, "8 minutes, was 30");
        assertEquals(604800, DESTINATION_TIMEOUT);
        assertEquals(1024, MAX_RECEIPTS);
        assertEquals(16, MAX_RATE_TIMESTAMPS);
        assertEquals(64, MAX_RANDOM_BLOBS, "was 128");
        assertEquals(512, LOCAL_CLIENT_CACHE_MAXSIZE);
    }

    @Test
    @DisplayName("Transport timings converted from the reference's seconds to milliseconds")
    void transportTimingsInMillis() {
        assertEquals(500, PATHFINDER_RW, "0.5 s");
        assertEquals(400, PATH_REQUEST_GRACE, "0.4 s, was 350 ms");
        assertEquals(1500, PATH_REQUEST_RG, "1.5 s");
        assertEquals(900, LINK_TIMEOUT, "STALE_TIME * 1.25, in seconds");
    }

    @Test
    @DisplayName("Link constants match RNS/Link.py")
    void linkConstants() {
        assertEquals(64, ECPUBSIZE);
        assertEquals(360, KEEPALIVE);
        assertEquals(2, STALE_FACTOR);
        assertEquals(720, STALE_TIME, "STALE_FACTOR * KEEPALIVE");
        assertEquals(5, STALE_GRACE, "was 2");
        assertEquals(6, TRAFFIC_TIMEOUT_FACTOR);
        assertEquals(4, KEEPALIVE_TIMEOUT_FACTOR);
        assertEquals(3, LINK_MTU_SIZE);
        assertEquals(0x1FFFFF, MTU_BYTEMASK);
        assertEquals(MODE_AES256_CBC, MODE_DEFAULT);
    }

    @Test
    @DisplayName("Identity constants match RNS/Identity.py")
    void identityConstants() {
        assertEquals(48, TOKEN_OVERHEAD);
        assertEquals(64, DERIVED_KEY_LENGTH, "AES-256");
        assertEquals(32, DERIVED_KEY_LENGTH_LEGACY);
        assertEquals(256, RATCHETSIZE);
        assertEquals(2592000, RATCHET_EXPIRY, "30 days");
        assertEquals(80, NAME_HASH_LENGTH);
    }

    /**
     * Raised from 14 to 16 in RNS 1.4.0. A lower local value means announces
     * this node emits are rejected by 1.4.0+ peers.
     */
    @Test
    @DisplayName("Discovery stamp value matches RNS 1.4.0+")
    void discoveryStampValue() {
        assertEquals(16, InterfaceAnnouncer.DEFAULT_STAMP_VALUE, "was 14");
    }
}
