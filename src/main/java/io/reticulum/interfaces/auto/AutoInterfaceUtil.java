package io.reticulum.interfaces.auto;

import inet.ipaddr.ipv6.IPv6Address;

import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.List;
import java.util.NoSuchElementException;

import static io.reticulum.interfaces.auto.AutoInterfaceConstant.ANDROID_PREDICATE;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.DARWIN_LOOPBACK_PREDICATE;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.DARWIN_PREDICATE;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.HAS_IPV6_ADDRESS;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.IGNORED_PREDICATE;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.IN_ALL_IGNORE_IFS;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.NOT_IN_ALLOWED_PREDICATE;
import static java.net.NetworkInterface.networkInterfaces;
import static java.util.stream.Collectors.toUnmodifiableList;

public interface AutoInterfaceUtil {

    default List<NetworkInterface> networkInterfaceList(AutoInterface instace) throws SocketException {
        return networkInterfaces()
                .filter(netIface -> DARWIN_PREDICATE.negate().test(netIface, instace))
                .filter(DARWIN_LOOPBACK_PREDICATE.negate())
                .filter(netIface -> ANDROID_PREDICATE.negate().test(netIface, instace))
                .filter(netIface -> IGNORED_PREDICATE.negate().test(netIface, instace))
                .filter(netIface -> NOT_IN_ALLOWED_PREDICATE.negate().test(netIface, instace))
                .filter(netIface -> IN_ALL_IGNORE_IFS.negate().test(netIface, instace))
                .filter(HAS_IPV6_ADDRESS)
                .collect(toUnmodifiableList());
    }

    default String getLocalIpv6Address(final NetworkInterface networkInterface) {
        return new IPv6Address(getInet6Address(networkInterface).getAddress()).toCompressedString();
    }

    default Inet6Address getInet6Address(final NetworkInterface networkInterface) {
        return (Inet6Address) networkInterface.inetAddresses()
                .filter(inetAddress -> inetAddress instanceof Inet6Address)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(networkInterface.toString()));
    }
}
