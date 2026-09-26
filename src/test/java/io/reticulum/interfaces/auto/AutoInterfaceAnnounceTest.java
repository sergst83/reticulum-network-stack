package io.reticulum.interfaces.auto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The discovery announce has to name its outgoing interface.
 * <p>
 * The discovery address is link-local scope multicast and carries no routing
 * information, so a socket that was never told which interface to use has
 * nothing to send on: the kernel answers {@code EADDRNOTAVAIL}, which surfaces
 * as {@code BindException: Cannot assign requested address}. A host with one
 * obvious interface gets away with it through the default multicast route; a
 * host with several (bridges, VPNs, USB adapters) does not, and then every
 * announce fails for the lifetime of the node — 3365 of them in one two-hour
 * run before this was fixed.
 * <p>
 * Skipped where the host has no multicast-capable interface with an IPv6
 * link-local address, and where the unselected send happens to work anyway,
 * since there is then nothing to demonstrate.
 */
class AutoInterfaceAnnounceTest {

    private static final String MCAST = "ff12:0:0:0:0:0:0:1";
    private static final int PORT = 29716;

    private static List<NetworkInterface> linkLocalInterfaces() throws Exception {
        var found = new ArrayList<NetworkInterface>();
        for (var iface : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!iface.isUp() || !iface.supportsMulticast() || iface.isLoopback()) {
                continue;
            }
            for (var addr : java.util.Collections.list(iface.getInetAddresses())) {
                if (addr instanceof Inet6Address && addr.isLinkLocalAddress()) {
                    found.add(iface);
                    break;
                }
            }
        }

        return found;
    }

    /** Sends the way the code used to: no outgoing interface named. */
    private static boolean unselectedSendWorks() {
        try (var socket = new DatagramSocket()) {
            var payload = new byte[]{1};
            socket.send(new DatagramPacket(payload, payload.length, InetAddress.getByName(MCAST), PORT));

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("naming the outgoing interface makes a link-local multicast send work")
    void selectingTheInterfaceIsWhatMakesTheSendWork() throws Exception {
        var interfaces = linkLocalInterfaces();
        assumeTrue(!interfaces.isEmpty(), "no multicast interface with an IPv6 link-local address");
        assumeTrue(!unselectedSendWorks(), "this host routes the unselected send anyway");

        var worked = false;
        for (var iface : interfaces) {
            try (var socket = new MulticastSocket()) {
                socket.setNetworkInterface(iface);
                var payload = new byte[]{1};
                socket.send(new DatagramPacket(payload, payload.length, InetAddress.getByName(MCAST), PORT));
                worked = true;
                break;
            } catch (Exception ignored) {
                // try the next interface; not every one has a usable link
            }
        }

        assertTrue(worked, "no interface accepted the announce even when named explicitly");
    }

    @Test
    @DisplayName("a failing interface is reported once, not on every announce")
    void announceFailureIsReportedOncePerInterface() throws Exception {
        // The reference reports possible carrier loss once per interface rather
        // than every few seconds for as long as the condition lasts
        // (RNS/Interfaces/AutoInterface.py:511-514).
        var iface = new AutoInterface();

        var listField = AutoInterface.class.getDeclaredField("interfaceList");
        listField.setAccessible(true);
        // A down/unusable interface set is fine: what is asserted is the bookkeeping.
        listField.set(iface, linkLocalInterfaces());

        Field failedField = AutoInterface.class.getDeclaredField("announceFailedInterfaces");
        failedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var failed = (Map<String, Boolean>) failedField.get(iface);

        Method announce = AutoInterface.class.getDeclaredMethod("peerAnnounce");
        announce.setAccessible(true);
        announce.invoke(iface);
        var afterFirst = failed.size();
        announce.invoke(iface);
        announce.invoke(iface);

        assertTrue(failed.size() == afterFirst,
                "the failing-interface set must not grow with repeated announces");
    }
}
