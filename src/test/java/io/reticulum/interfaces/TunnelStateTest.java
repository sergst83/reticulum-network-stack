package io.reticulum.interfaces;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interfaces must keep the tunnel state Transport hands them. They used to fall
 * back to the {@link ConnectionInterface} no-op defaults: the tunnel id was
 * dropped, so a transport node never associated a path with a tunnel and lost
 * every path learned through a client when it restarted, until the next announce.
 */
class TunnelStateTest {

    private static final class TestInterface extends AbstractConnectionInterface {
        @Override public void processIncoming(byte[] data) { }
        @Override public void processOutgoing(byte[] data) { }
        @Override public void launch() { }
        @Override public void run() { }
    }

    @Test
    void keepsTunnelId() {
        ConnectionInterface iface = new TestInterface();
        assertNull(iface.getTunnelId());

        var tunnelId = new byte[]{1, 2, 3};
        iface.setTunnelId(tunnelId);
        assertArrayEquals(tunnelId, iface.getTunnelId());

        iface.setTunnelId(null);
        assertNull(iface.getTunnelId());
    }

    @Test
    void requestTunnelWithoutTransportLeavesItWanted() {
        var iface = new TestInterface();
        assertFalse(iface.wantsTunnel());

        // No Transport running: Transport start-up synthesizes it later
        iface.requestTunnel();
        assertTrue(iface.wantsTunnel());

        iface.setWantsTunnel(false);
        assertFalse(iface.wantsTunnel());
    }
}
