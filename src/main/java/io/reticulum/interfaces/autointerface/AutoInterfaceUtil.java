package io.reticulum.interfaces.autointerface;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.List;

import static io.reticulum.interfaces.autointerface.AutoInterfaceConstant.ANDROID_PREDICATE;
import static io.reticulum.interfaces.autointerface.AutoInterfaceConstant.DARWIN_LOOPBACK_PREDICATE;
import static io.reticulum.interfaces.autointerface.AutoInterfaceConstant.DARWIN_PREDICATE;
import static io.reticulum.interfaces.autointerface.AutoInterfaceConstant.HAS_IPV6_ADDRESS;
import static io.reticulum.interfaces.autointerface.AutoInterfaceConstant.IGNORED_PREDICATE;
import static io.reticulum.interfaces.autointerface.AutoInterfaceConstant.NOT_IN_ALLOWED_PREDICATE;
import static java.net.NetworkInterface.networkInterfaces;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toUnmodifiableList;

public interface AutoInterfaceUtil {

    default List<NetworkInterface> networkInterfaceList(AutoInterface instace) throws SocketException {
        return networkInterfaces()
                .filter(netIface -> DARWIN_PREDICATE.negate().test(netIface, instace))
                .filter(DARWIN_LOOPBACK_PREDICATE.negate())
                .filter(netIface -> ANDROID_PREDICATE.negate().test(netIface, instace))
                .filter(netIface -> IGNORED_PREDICATE.negate().test(netIface, instace))
                .filter(netIface -> NOT_IN_ALLOWED_PREDICATE.test(netIface, instace))
                .filter(HAS_IPV6_ADDRESS)
                .collect(toUnmodifiableList());
    }

    default String getLocalIpv6Address(final NetworkInterface networkInterface) {
        return getInet6Address(networkInterface)
                .getHostAddress()
                .split("%")[0];
    }

    default Inet6Address getInet6Address(final NetworkInterface networkInterface) {
        return (Inet6Address) networkInterface.inetAddresses()
                .filter(inetAddress -> inetAddress instanceof Inet6Address)
                .filter(InetAddress::isLinkLocalAddress)
                .findFirst()
                .orElseThrow();
    }

    default long secToMillisec(double sec) {
        return (long) (sec * SECONDS.toMillis(1));
    }
}
