package io.reticulum;

import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.interfaces.InterfaceMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.reticulum.constant.TransportConstant.BOUNDARY_SEARCH_MODES;
import static io.reticulum.constant.TransportConstant.DISCOVER_PATHS_FOR;
import static io.reticulum.interfaces.InterfaceMode.MODE_ACCESS_POINT;
import static io.reticulum.interfaces.InterfaceMode.MODE_BOUNDARY;
import static io.reticulum.interfaces.InterfaceMode.MODE_FULL;
import static io.reticulum.interfaces.InterfaceMode.MODE_GATEWAY;
import static io.reticulum.interfaces.InterfaceMode.MODE_INTERNAL;
import static io.reticulum.interfaces.InterfaceMode.MODE_ROAMING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Covers announce propagation across interface modes, including the
 * internal mode and its two options introduced in RNS 1.3.7.
 * <p>
 * The branch order in {@code RNS/Transport.py:1458-1517} is load-bearing —
 * several conditions overlap — so these cases pin the outcome rather than the
 * shape of the code.
 */
class AnnounceModeGateTest {

    private static ConnectionInterface iface(InterfaceMode mode) {
        var connectionInterface = mock(ConnectionInterface.class);
        lenient().when(connectionInterface.getMode()).thenReturn(mode);
        lenient().when(connectionInterface.isAnnouncesFromInternal()).thenReturn(true);
        lenient().when(connectionInterface.getAnnouncesToInternal()).thenReturn(null);
        lenient().when(connectionInterface.getInterfaceName()).thenReturn("test-" + mode);

        return connectionInterface;
    }

    private static boolean allowed(ConnectionInterface out, ConnectionInterface from, boolean local) {
        return Transport.announceModeGate(out, from, local) == null;
    }

    @Test
    @DisplayName("MODE_INTERNAL exists with the reference's value")
    void modeInternalExists() {
        assertEquals((byte) 0x07, MODE_INTERNAL.getModeValue());
        assertEquals(MODE_INTERNAL, InterfaceMode.parseName("internal"));
    }

    @Test
    @DisplayName("Internal mode participates in path discovery; boundary search is gateway/boundary")
    void modeLists() {
        assertTrue(DISCOVER_PATHS_FOR.contains(MODE_INTERNAL), "MODE_INTERNAL must be in DISCOVER_PATHS_FOR");
        assertTrue(DISCOVER_PATHS_FOR.contains(MODE_ACCESS_POINT));
        assertTrue(DISCOVER_PATHS_FOR.contains(MODE_GATEWAY));
        assertTrue(DISCOVER_PATHS_FOR.contains(MODE_ROAMING));
        assertFalse(DISCOVER_PATHS_FOR.contains(MODE_BOUNDARY));

        assertEquals(2, BOUNDARY_SEARCH_MODES.size());
        assertTrue(BOUNDARY_SEARCH_MODES.contains(MODE_BOUNDARY));
        assertTrue(BOUNDARY_SEARCH_MODES.contains(MODE_GATEWAY));
    }

    @Test
    @DisplayName("An announce with no next hop and no local destination is blocked")
    void noNextHopBlocked() {
        assertNotNull(Transport.announceModeGate(iface(MODE_FULL), null, false));
        assertNull(Transport.announceModeGate(iface(MODE_FULL), null, true),
                "an instance-local destination needs no next hop");
    }

    @Test
    @DisplayName("announces_from_internal=false blocks announces whose next hop is internal")
    void announcesFromInternalBlocks() {
        var out = iface(MODE_FULL);
        lenient().when(out.isAnnouncesFromInternal()).thenReturn(false);

        assertFalse(allowed(out, iface(MODE_INTERNAL), false),
                "an internal-mode next hop must be blocked when announces_from_internal is off");
        assertTrue(allowed(out, iface(MODE_FULL), false),
                "other next-hop modes are unaffected");
        assertTrue(allowed(iface(MODE_FULL), iface(MODE_INTERNAL), false),
                "the default announces_from_internal=true permits it");
    }

    @Test
    @DisplayName("Announces into an internal-mode interface are blocked from boundary next hops")
    void internalOutBlocksBoundary() {
        assertFalse(allowed(iface(MODE_INTERNAL), iface(MODE_BOUNDARY), false));
        assertTrue(allowed(iface(MODE_INTERNAL), iface(MODE_FULL), false));
        assertTrue(allowed(iface(MODE_INTERNAL), iface(MODE_ROAMING), false),
                "roaming next hops are not blocked into internal, unlike into roaming/boundary");
    }

    @Test
    @DisplayName("announces_to_internal=true overrides the boundary block into internal")
    void announcesToInternalOverrides() {
        var from = iface(MODE_BOUNDARY);
        lenient().when(from.getAnnouncesToInternal()).thenReturn(true);

        assertTrue(allowed(iface(MODE_INTERNAL), from, false),
                "announces_to_internal must permit an otherwise-blocked boundary next hop");
    }

    @Test
    @DisplayName("An instance-local destination bypasses the internal-mode check")
    void localDestinationBypassesInternal() {
        assertTrue(allowed(iface(MODE_INTERNAL), iface(MODE_BOUNDARY), true));
    }

    @Test
    @DisplayName("AP mode blocks outgoing announces regardless of next hop")
    void accessPointAlwaysBlocks() {
        assertFalse(allowed(iface(MODE_ACCESS_POINT), iface(MODE_FULL), false));
        assertFalse(allowed(iface(MODE_ACCESS_POINT), iface(MODE_FULL), true),
                "AP mode blocks even instance-local announces");
    }

    @Test
    @DisplayName("Roaming and boundary keep their pre-existing behaviour")
    void roamingAndBoundaryUnchanged() {
        // roaming out: blocked from roaming and boundary next hops
        assertFalse(allowed(iface(MODE_ROAMING), iface(MODE_ROAMING), false));
        assertFalse(allowed(iface(MODE_ROAMING), iface(MODE_BOUNDARY), false));
        assertTrue(allowed(iface(MODE_ROAMING), iface(MODE_FULL), false));
        assertTrue(allowed(iface(MODE_ROAMING), iface(MODE_ROAMING), true));

        // boundary out: blocked from roaming only
        assertFalse(allowed(iface(MODE_BOUNDARY), iface(MODE_ROAMING), false));
        assertTrue(allowed(iface(MODE_BOUNDARY), iface(MODE_BOUNDARY), false));
        assertTrue(allowed(iface(MODE_BOUNDARY), iface(MODE_ROAMING), true));
    }

    @Test
    @DisplayName("A next hop with no configured mode blocks mode-sensitive interfaces")
    void unconfiguredNextHopMode() {
        var from = iface(MODE_FULL);
        lenient().when(from.getMode()).thenReturn(null);

        assertFalse(allowed(iface(MODE_INTERNAL), from, false));
        assertFalse(allowed(iface(MODE_ROAMING), from, false));
        assertFalse(allowed(iface(MODE_BOUNDARY), from, false));
        assertTrue(allowed(iface(MODE_FULL), from, false),
                "a full-mode interface does not inspect the next hop's mode");
    }

    @Test
    @DisplayName("Full-mode interfaces pass the gate and fall through to announce capping")
    void fullModePasses() {
        assertTrue(allowed(iface(MODE_FULL), iface(MODE_FULL), false));
        assertTrue(allowed(iface(MODE_GATEWAY), iface(MODE_ROAMING), false));
    }
}
