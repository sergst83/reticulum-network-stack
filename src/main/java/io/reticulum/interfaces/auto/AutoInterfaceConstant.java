package io.reticulum.interfaces.auto;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static lombok.AccessLevel.PRIVATE;
import static org.apache.commons.collections4.CollectionUtils.containsAny;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;
import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.apache.commons.lang3.SystemUtils.OS_NAME;

@NoArgsConstructor(access = PRIVATE)
@Slf4j
public final class AutoInterfaceConstant {
    static final int HW_MTU = 1064;
    static final long PEERING_TIMEOUT = 8_000;
    static final int DEFAULT_DISCOVERY_PORT = 29716;
    static final int DEFAULT_DATA_PORT = 42671;
    static final int DEFAULT_IFAC_SIZE = 16;
    static final List<String> ALL_IGNORE_IFS     = List.of("lo0");
    static final List<String> DARWIN_IGNORE_IFS  = List.of("awdl0", "llw0", "lo0", "en5");
    static final List<String> ANDROID_IGNORE_IFS = List.of("dummy0", "lo", "tun0");
    static final int BITRATE_GUESS = 10 * 1000 * 1000;
    static final  int MULTI_IF_DEQUE_LEN = 64;

    static final BiPredicate<NetworkInterface, AutoInterface> DARWIN_PREDICATE = (netIface, autoInterface) -> {
        var result = OS_NAME.toLowerCase().contains("darwin")
                && DARWIN_IGNORE_IFS.contains(netIface.getName().toLowerCase())
                && isFalse(autoInterface.getAllowedInterfaces().contains(netIface.getName()));
        if (result) {
            log.trace("{} skipping Darwin AWDL or tethering interface {}", autoInterface.getInterfaceName(), netIface.getName());
        }

        return result;
    };

    static final Predicate<NetworkInterface> DARWIN_LOOPBACK_PREDICATE = netIface -> {
        var result = OS_NAME.toLowerCase().contains("darwin") && "lo0".equalsIgnoreCase(netIface.getName());
        if (result) {
            log.trace("{} skipping Darwin loopback interface {}", AutoInterface.class, netIface.getName());
        }

        return result;
    };

    static final BiPredicate<NetworkInterface, AutoInterface> ANDROID_PREDICATE = (netIface, autoInterface) -> {
        var result = OS_NAME.toLowerCase().contains("android")
                && ANDROID_IGNORE_IFS.contains(netIface.getName().toLowerCase())
                && isFalse(autoInterface.getAllowedInterfaces().contains(netIface.getName()));
        if (result) {
            log.trace("{} skipping Android system interface {}", autoInterface.getInterfaceName(), netIface.getName());
        }

        return result;
    };

    static final BiPredicate<NetworkInterface, AutoInterface> IGNORED_PREDICATE = (netIface, autoInterface) -> {
        var result = autoInterface.getIgnoredInterfaces().contains(netIface.getName().toLowerCase());
        if (result) {
            log.trace("{} ignoring disallowed interface {}", autoInterface.getInterfaceName(), netIface.getName());
        }

        return result;
    };

    static final BiPredicate<NetworkInterface, AutoInterface> NOT_IN_ALLOWED_PREDICATE = (netIface, autoInterface) -> {
        var disallowed = isNotEmpty(autoInterface.getAllowedInterfaces()) && containsAny(autoInterface.getAllowedInterfaces(), netIface);
        if (disallowed) {
            log.trace("{} ignoring interface {} since it was not allowed", autoInterface.getInterfaceName(), netIface.getName());
        }

        return disallowed;
    };

    static final BiPredicate<NetworkInterface, AutoInterface> IN_ALL_IGNORE_IFS = (netIface, autoInterface) -> {
        var result = ALL_IGNORE_IFS.contains(netIface.getName().toLowerCase());
        if (result) {
            log.trace("{} skipping interface {}", autoInterface.getInterfaceName(), netIface.getName());
        }

        return result;
    };

    static final Predicate<NetworkInterface> HAS_IPV6_ADDRESS = netIface -> netIface.inetAddresses()
            .anyMatch(inetAddress -> inetAddress instanceof Inet6Address);

    /**
     * Only keep interfaces that can actually carry multicast discovery traffic:
     * up, multicast-capable and non-loopback. Without this, a device that still
     * exposes a stale link-local IPv6 address but has no carrier/route (e.g. an
     * unplugged USB-ethernet dongle) stays in the announce rotation and every
     * peerAnnounce() send fails with "Network is unreachable" — observed spamming
     * ERROR thousands of times over a run. isUp()/supportsMulticast() throw
     * SocketException, so treat any failure as "not usable" and drop the interface.
     */
    static final Predicate<NetworkInterface> IS_USABLE = netIface -> {
        try {
            return netIface.isUp() && netIface.supportsMulticast() && isFalse(netIface.isLoopback());
        } catch (java.net.SocketException e) {
            log.debug("Skipping interface {} while checking usability: {}", netIface.getName(), e.getMessage());
            return false;
        }
    };
}
