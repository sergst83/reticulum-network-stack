package io.reticulum.interfaces.auto;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static lombok.AccessLevel.PRIVATE;
import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.apache.commons.lang3.SystemUtils.OS_NAME;

@NoArgsConstructor(access = PRIVATE)
@Slf4j
public final class AutoInterfaceConstant {
    static final int HW_MTU = 1064;
    static final double PEERING_TIMEOUT = 7.5;
    static final int DEFAULT_DISCOVERY_PORT = 29716;
    static final int DEFAULT_DATA_PORT = 42671;
    static final int DEFAULT_IFAC_SIZE = 16;
    static final List<String> DARWIN_IGNORE_IFS = List.of("awdl0", "llw0", "lo0", "en5");
    static final List<String> ANDROID_IGNORE_IFS = List.of("dummy0", "lo", "tun0");
    static final boolean IN = true;
    static final int BITRATE_GUESS = 10 * 1000 * 1000;

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
        var result = autoInterface.getAllowedInterfaces().size() > 0
                && isFalse(autoInterface.getAllowedInterfaces().contains(netIface.getName().toLowerCase()));
        if (result) {
            log.trace("{} ignoring interface {} since it was not allowed", autoInterface.getInterfaceName(), netIface.getName());
        }

        return result;
    };

    static final Predicate<NetworkInterface> HAS_IPV6_ADDRESS = netIface -> netIface.inetAddresses()
            .anyMatch(inetAddress -> inetAddress instanceof Inet6Address);
}
