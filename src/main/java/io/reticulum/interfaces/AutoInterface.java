package io.reticulum.interfaces;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.reticulum.interfaces.AutoInterfaceConstant.ANDROID_PREDICATE;
import static io.reticulum.interfaces.AutoInterfaceConstant.DARWIN_LOOPBACK_PREDICATE;
import static io.reticulum.interfaces.AutoInterfaceConstant.DARWIN_PREDICATE;
import static io.reticulum.interfaces.AutoInterfaceConstant.DEFAULT_DATA_PORT;
import static io.reticulum.interfaces.AutoInterfaceConstant.DEFAULT_DISCOVERY_PORT;
import static io.reticulum.interfaces.AutoInterfaceConstant.DEFAULT_IFAC_SIZE;
import static io.reticulum.interfaces.AutoInterfaceConstant.HAS_IPV6_ADDRESS;
import static io.reticulum.interfaces.AutoInterfaceConstant.IGNORED_PREDICATE;
import static io.reticulum.interfaces.AutoInterfaceConstant.NOT_IN_ALLOWED_PREDICATE;
import static io.reticulum.interfaces.AutoInterfaceConstant.PEERING_TIMEOUT;
import static java.lang.Byte.toUnsignedInt;
import static java.lang.String.format;
import static java.net.NetworkInterface.networkInterfaces;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.Executors.callable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toUnmodifiableList;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.commons.codec.digest.DigestUtils.getSha256Digest;
import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;

@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@ToString(callSuper = true)
@Slf4j
@Setter
@Getter
public class AutoInterface extends AbstractConnectionInterface {

    {
        ifacSize = DEFAULT_IFAC_SIZE;
    }

    @JsonProperty("outgoing")
    private boolean OUT = false;

    @RequiredArgsConstructor
    @Getter
    public enum DiscoveryScope {
        SCOPE_LINK("link", "2"),
        SCOPE_ADMIN("admin", "4"),
        SCOPE_SITE("site", "5"),
        SCOPE_ORGANISATION("organisation", "8"),
        SCOPE_GLOBAL("global", "e"),
        ;

        private final String scopeName;
        private final String scopeValue;

        @JsonCreator
        public static DiscoveryScope parseByScopeName(String scopeName) {
            return Arrays.stream(DiscoveryScope.values())
                    .filter(discoveryScope -> discoveryScope.getScopeName().equals(scopeName))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @JsonProperty("group_id")
    private String groupId = "reticulum";

    @JsonProperty("discovery_scope")
    private DiscoveryScope discoveryScope = DiscoveryScope.SCOPE_LINK;

    @JsonProperty("data_port")
    private int dataPort = DEFAULT_DATA_PORT;

    @JsonProperty("discovery_port")
    private int discoveryPort = DEFAULT_DISCOVERY_PORT;

    @JsonProperty("devices")
    private List<String> allowedInterfaces = List.of();

    @JsonProperty("ignored_interfaces")
    private List<String> ignoredInterfaces = List.of();

    private int rxb = 0;
    private int txb = 0;
    private boolean online = false;
    private Map<?, ?> peers = Map.of();
    private List<?> linkLocalAddresses = List.of();
    private Map<?, ?> adoptedInterfaces = Map.of();
    private Map<?, ?> interfaceServers = Map.of();
    private Map<?, ?> multicastEchoes = Map.of();
    private Map<?, ?> timedOutInterfaces = Map.of();
    private boolean carrierChanged;
    private boolean receives;

    private Object outboundUdpSocket;

    private double announceInterval = PEERING_TIMEOUT / 6;
    private double peerJobInterval = PEERING_TIMEOUT * 1.1;
    private double peeringTimeout = PEERING_TIMEOUT;
    private double multicastEchoTimeout = PEERING_TIMEOUT / 2;

    private List<NetworkInterface> interfaceList = List.of();

    @Setter(PRIVATE)
    @Getter(PRIVATE)
    private ExecutorService peerAnnounceScheduledThreadPool;

    @Override
    public void setEnabled(boolean enabled) {
        if (IS_OS_WINDOWS) {
            log.error(
                    "AutoInterface is not currently supported on Windows, disabling interface.\n"
                            + "Please remove this AutoInterface instance from your configuration file.\n"
                            + "You will have to manually configure other interfaces for connectivity."
            );
            super.setEnabled(false);
        } else {
            super.setEnabled(enabled);
        }
    }
    
    @SneakyThrows
    public void init() {
        interfaceList = networkInterfaces()
                .filter(netIface -> DARWIN_PREDICATE.negate().test(netIface, this))
                .filter(DARWIN_LOOPBACK_PREDICATE.negate())
                .filter(netIface -> ANDROID_PREDICATE.negate().test(netIface, this))
                .filter(netIface -> IGNORED_PREDICATE.negate().test(netIface, this))
                .filter(netIface -> NOT_IN_ALLOWED_PREDICATE.test(netIface, this))
                .filter(HAS_IPV6_ADDRESS)
                .collect(toUnmodifiableList());

        // TODO: 23.02.2023 создаем поток, в котором будут слушаться multicast discovery сообщения и добавляться в список пиров

        //создаем потоки, которые будет рассылать по multicast сообщения и определенным интервалом
        peerAnnounceScheduledThreadPool = Executors.newScheduledThreadPool(interfaceList.size());
        peerAnnounceScheduledThreadPool.invokeAll(
                interfaceList.stream()
                        .map(iface -> callable(() -> peerAnnounce(iface)))
                        .collect(toList()),
                (long) (getAnnounceInterval() * SECONDS.toMillis(1)),
                MILLISECONDS
        );

        if (interfaceList.size() == 0) {
            log.trace("{} could not autoconfigure. This interface currently provides no connectivity.", getName());
        } else {
            // TODO: 23.02.2023 Запускаем udp сервера на всех интерфейсам в своих потоках для слушания соединений
            // TODO: 23.02.2023 запускаем peer job которая проверяет пиров от которых давно не было анонсов и перезапускает udp сервер если там изменился адрес
        }
    }

    public byte[] getGroupHash() {
        return getSha256Digest().digest(getGroupId().getBytes(UTF_8));
    }

    public String getMcastDiscoveryAddress() {
        var groupHash = getGroupHash();
        var sj = new StringJoiner(":")
                .add("ff1" + getDiscoveryScope().getScopeValue())
                .add("0");
        for (int i = 2; i <= 12; i += 2) {
            sj.add(format("%02x", toUnsignedInt(groupHash[i + 1]) + (toUnsignedInt(groupHash[i]) << 8)));
        }

        return sj.toString();
    }

    private void peerAnnounce(final NetworkInterface networkInterface) {
        var discoveryToken = getSha256Digest().digest(
                (getGroupId() + getLocalIpv6Address(networkInterface)).getBytes(UTF_8)
        );
        try(var multicastSocket = new MulticastSocket(getDiscoveryPort())) {
            var group = InetAddress.getByAddress(getMcastDiscoveryAddress().getBytes(UTF_8));
            multicastSocket.joinGroup(group);
            multicastSocket.send(new DatagramPacket(discoveryToken, discoveryToken.length, group, getDiscoveryPort()));
            multicastSocket.leaveGroup(group);
        } catch (IOException e) {
            log.error("Error while send announce on interface {}", networkInterface, e);
        }
    }

    private String getLocalIpv6Address(final NetworkInterface networkInterface) {
        return getInet6Address(networkInterface)
                .getHostAddress()
                .split("%")[0];
    }

    private Inet6Address getInet6Address(final NetworkInterface networkInterface) {
        return (Inet6Address) networkInterface.inetAddresses()
                .filter(inetAddress -> inetAddress instanceof Inet6Address)
                .filter(InetAddress::isLinkLocalAddress)
                .findFirst()
                .orElseThrow();
    }
}
