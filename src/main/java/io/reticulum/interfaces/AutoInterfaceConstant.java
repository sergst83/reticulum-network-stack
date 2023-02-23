package io.reticulum.interfaces;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    static final List<String> DARWIN_IGNORE_IFS  = List.of("awdl0", "llw0", "lo0", "en5");
    static final List<String> ANDROID_IGNORE_IFS = List.of("dummy0", "lo", "tun0");
    static final boolean IN = true;

    static final BiPredicate<NetworkInterface, List<String>> DARWIN_PREDICATE = (netIface, allowedInterfaces) -> {
        var result = OS_NAME.toLowerCase().contains("darwin")
                && DARWIN_IGNORE_IFS.contains(netIface.getName().toLowerCase())
                && isFalse(allowedInterfaces.contains(netIface.getName()));
        if (result) {
            log.trace("{} skipping Darwin AWDL or tethering interface {}", AutoInterface.class, netIface.getName());
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

    static final BiPredicate<NetworkInterface, List<String>> ANDROID_PREDICATE = (netIface, allowedInterfaces) -> {
        var result = OS_NAME.toLowerCase().contains("android")
                && ANDROID_IGNORE_IFS.contains(netIface.getName().toLowerCase())
                && isFalse(allowedInterfaces.contains(netIface.getName()));
        if (result) {
            log.trace("{} skipping Android system interface {}", AutoInterface.class, netIface.getName());
        }

        return result;
    };

    static final BiPredicate<NetworkInterface, List<String>> IGNORED_PREDICATE = (netIface, ignoredInterfaces) -> {
        var result = ignoredInterfaces.contains(netIface.getName().toLowerCase());
        if (result) {
            log.trace("{} ignoring disallowed interface {}", AutoInterface.class, netIface.getName());
        }

        return result;
    };

    static final BiPredicate<NetworkInterface, List<String>> NOT_IN_ALLOWED_PREDICATE = (netIface, allowedInterfaces) -> {
        var result = allowedInterfaces.size() > 0 && isFalse(allowedInterfaces.contains(netIface.getName().toLowerCase()));
        if (result) {
            log.trace("{} ignoring interface {} since it was not allowed", AutoInterface.class, netIface.getName());
        }

        return result;
    };
}
