package io.reticulum.link;

import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.interfaces.auto.AutoInterface;
import io.reticulum.interfaces.backbone.BackboneClientInterface;
import io.reticulum.interfaces.backbone.BackboneServerInterface;
import io.reticulum.interfaces.tcp.TCPClientInterface;
import io.reticulum.interfaces.tcp.TCPServerInterface;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.reticulum.constant.LinkConstant.MODE_AES256_CBC;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Parity tests for hardware-MTU derivation and link-request MTU clamping.
 * <p>
 * Both mechanisms were missing entirely, which is why every Java↔Python link
 * negotiated 16384 while Java believed it had offered 262144: the reference was
 * clamping on our behalf. Java↔Java had nothing doing the clamping.
 * <p>
 * Every expected value here was produced by running the reference
 * (Python RNS 1.5.2) directly — {@code Interface.optimise_mtu()} for the table
 * and {@code Link.signalling_bytes} for the byte vectors.
 */
class LinkMtuClampTest {

    /** {bitrate, expected HW_MTU} straight out of the reference. */
    private static final Object[][] OPTIMISE_MTU_VECTORS = {
            {0, null},
            {1, null},
            {62_499, null},
            {62_500, 1024},
            {999_999, 1024},
            {1_000_000, 2048},
            {1_999_999, 2048},
            {2_000_000, 4096},
            {4_999_999, 4096},
            {5_000_000, 8192},
            {9_999_999, 8192},
            {10_000_000, 16384},
            {99_999_999, 16384},
            {100_000_000, 32768},
            {199_999_999, 32768},
            {200_000_000, 65536},
            {399_999_999, 65536},
            {400_000_000, 131072},
            {749_999_999, 131072},
            {750_000_000, 262144},
            {999_999_999, 262144},
            {1_000_000_000, 524288},
            {Integer.MAX_VALUE, 524288},
    };

    @Test
    @DisplayName("optimisedMtu matches the reference table at every boundary")
    void optimisedMtuParity() {
        for (var vector : OPTIMISE_MTU_VECTORS) {
            var bitrate = (Integer) vector[0];
            var expected = (Integer) vector[1];
            assertEquals(expected, ConnectionInterface.optimisedMtu(bitrate),
                    "bitrate " + bitrate);
        }
        assertNull(ConnectionInterface.optimisedMtu(null));
    }

    @Test
    @DisplayName("a TCP interface lands on the reference's 16384, not its class ceiling")
    void tcpInterfacesOptimiseToReferenceValue() {
        // The per-class HW_MTU of 262144 is only a ceiling. The reference's
        // BITRATE_GUESS of 10 Mbps puts a real TCP interface at 16384, which is
        // exactly the MTU Python confirmed on every step-1 link.
        var client = new TCPClientInterface();
        assertEquals(262_144, client.getHwMtu(), "seeded with the class ceiling");
        client.optimiseMtu();
        assertEquals(16_384, client.getHwMtu());

        var server = new TCPServerInterface();
        server.optimiseMtu();
        assertEquals(16_384, server.getHwMtu());
    }

    @Test
    @DisplayName("Backbone interfaces optimise to 32768 at the reference's 100 Mbps guess")
    void backboneInterfacesOptimise() {
        var server = new BackboneServerInterface();
        server.optimiseMtu();
        assertEquals(32_768, server.getHwMtu());

        var client = new BackboneClientInterface();
        client.optimiseMtu();
        assertEquals(32_768, client.getHwMtu());
    }

    @Test
    @DisplayName("a fixed-MTU interface is left alone by optimiseMtu")
    void fixedMtuInterfaceIsNotRescaled() {
        // AutoInterface declares FIXED_MTU, not AUTOCONFIGURE_MTU: its 1196 comes
        // from the Ethernet payload it rides on, not from its bitrate.
        var auto = new AutoInterface();
        assertEquals(1196, auto.getHwMtu());
        auto.optimiseMtu();
        assertEquals(1196, auto.getHwMtu(), "optimise_mtu() is a no-op without AUTOCONFIGURE_MTU");
    }

    @Test
    @DisplayName("a configured bitrate changes the hardware MTU")
    void configuredBitrateDrivesMtu() {
        var iface = new TCPClientInterface();
        iface.setBitrate(1_000_000_000);
        iface.optimiseMtu();
        assertEquals(524_288, iface.getHwMtu());

        iface.setBitrate(1_000_000);
        iface.optimiseMtu();
        assertEquals(2048, iface.getHwMtu());
    }

    @Test
    @DisplayName("an AutoInterface datagram at the full hardware MTU is not truncated")
    void autoInterfaceCarriesAFullMtuDatagram() throws Exception {
        // AutoInterface rides UDP, where an undersized receive buffer truncates
        // silently — no error, just a short frame that fails to unpack later.
        // Its receive loop sizes the buffer at AutoInterfaceConstant.HW_MTU, so
        // a datagram at exactly that size must survive intact. A two-node live
        // test is not possible on a single host (both peers would share one
        // link-local address and could not tell each other apart), so the risk
        // is pinned down here instead.
        var hwMtu = new AutoInterface().getHwMtu();
        assertEquals(1196, hwMtu);

        var payload = new byte[hwMtu];
        new java.util.Random(20260906L).nextBytes(payload);

        try (var receiver = new java.net.DatagramSocket(0, java.net.InetAddress.getLoopbackAddress());
             var sender = new java.net.DatagramSocket()) {
            receiver.setSoTimeout(5000);
            sender.send(new java.net.DatagramPacket(payload, payload.length,
                    java.net.InetAddress.getLoopbackAddress(), receiver.getLocalPort()));

            // Sized exactly as AutoInterface.initNetworkInterfaceServer() sizes it
            var buf = new byte[hwMtu];
            var received = new java.net.DatagramPacket(buf, buf.length);
            receiver.receive(received);

            assertEquals(payload.length, received.getLength(), "datagram was truncated");
            assertArrayEquals(payload, java.util.Arrays.copyOf(received.getData(), received.getLength()));
        }
    }

    @Test
    @DisplayName("the AutoInterface link MDU leaves room for the packet header")
    void autoInterfaceMduFitsItsHardwareMtu() {
        var hwMtu = new AutoInterface().getHwMtu();
        var mdu = Link.mduForMtu(hwMtu);
        org.junit.jupiter.api.Assertions.assertTrue(mdu > 0 && mdu < hwMtu,
                "MDU " + mdu + " must sit below the hardware MTU " + hwMtu);
    }

    // -- clamping the signalling bytes in place -----------------------------
    //
    // 64 bytes of key material (0x00..0x3f) followed by signalling bytes, all
    // produced by the reference.

    private static final String LR_262144 =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
          + "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
          + "240000";

    private static final String LR_16384 =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
          + "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
          + "204000";

    private static final String LR_STRIPPED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
          + "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f";

    private static byte[] hex(String s) throws Exception {
        return Hex.decodeHex(s);
    }

    @Test
    @DisplayName("withClampedMtu reproduces the reference's rewritten link request")
    void clampMatchesReference() throws Exception {
        var original = hex(LR_262144);
        assertEquals(262_144, Link.mtuFromLrPacket(original));
        assertEquals(MODE_AES256_CBC, Link.modeFromLrPacket(original));

        var clamped = Link.withClampedMtu(original, 16_384, MODE_AES256_CBC);
        assertArrayEquals(hex(LR_16384), clamped);

        // and the reference decodes what we wrote
        assertEquals(16_384, Link.mtuFromLrPacket(clamped));
        assertEquals(MODE_AES256_CBC, Link.modeFromLrPacket(clamped));

        // the key material must survive untouched — it is what the handshake uses
        assertArrayEquals(hex(LR_STRIPPED), java.util.Arrays.copyOf(clamped, 64));
    }

    @Test
    @DisplayName("withoutMtuSignalling drops exactly the three signalling bytes")
    void stripMatchesReference() throws Exception {
        assertArrayEquals(hex(LR_STRIPPED), Link.withoutMtuSignalling(hex(LR_262144)));
        // A stripped request no longer carries an MTU, so the link stays at the default
        assertNull(Link.mtuFromLrPacket(Link.withoutMtuSignalling(hex(LR_262144))));
    }

    @Test
    @DisplayName("clamping round-trips across the whole MTU table")
    void clampRoundTripsForEveryTableValue() throws Exception {
        for (var vector : OPTIMISE_MTU_VECTORS) {
            var mtu = (Integer) vector[1];
            if (mtu == null) {
                continue;
            }
            var clamped = Link.withClampedMtu(hex(LR_262144), mtu, MODE_AES256_CBC);
            assertEquals(mtu, Link.mtuFromLrPacket(clamped), "mtu " + mtu);
            assertEquals(MODE_AES256_CBC, Link.modeFromLrPacket(clamped), "mtu " + mtu);
        }
    }
}
