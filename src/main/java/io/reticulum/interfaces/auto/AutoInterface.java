package io.reticulum.interfaces.auto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import inet.ipaddr.ipv6.IPv6Address;
import io.reticulum.Transport;
import io.reticulum.interfaces.AbstractConnectionInterface;
import io.reticulum.utils.Scheduler;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.collections4.CollectionUtils;

import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.reticulum.interfaces.auto.AutoInterfaceConstant.BITRATE_GUESS;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.DEFAULT_DATA_PORT;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.DEFAULT_DISCOVERY_PORT;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.DEFAULT_IFAC_SIZE;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.MULTI_IF_DEQUE_LEN;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.PEERING_TIMEOUT;
import static io.reticulum.interfaces.auto.DiscoveryScope.SCOPE_LINK;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static io.reticulum.utils.IdentityUtils.fullHash;
import static java.lang.Byte.toUnsignedInt;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.nonNull;
import static java.util.concurrent.Executors.defaultThreadFactory;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;

@NoArgsConstructor
@Slf4j
@Setter
@Getter
public class AutoInterface extends AbstractConnectionInterface implements AutoInterfaceUtil {

    {
        ifacSize = DEFAULT_IFAC_SIZE;
        bitrate = BITRATE_GUESS;
        IN = true;
        OUT = true;
    }

    @JsonProperty("group_id")
    private String groupId = "reticulum";

    @JsonProperty("discovery_scope")
    private DiscoveryScope discoveryScope = SCOPE_LINK;

    @JsonProperty("data_port")
    private int dataPort = DEFAULT_DATA_PORT;

    @JsonProperty("discovery_port")
    private int discoveryPort = DEFAULT_DISCOVERY_PORT;

    @JsonProperty("devices")
    private List<String> allowedInterfaces = List.of();

    @JsonProperty("ignored_interfaces")
    private List<String> ignoredInterfaces = List.of();

    @JsonIgnore
    private Map<InetAddress, Instant> peers = new ConcurrentHashMap<>();
    @JsonIgnore
    private AtomicBoolean carrierChanged = new AtomicBoolean(false);

    private Deque<String> mifDeque = new ConcurrentLinkedDeque<>();

    private String mcastDiscoveryAddress;
    private long announceInterval = PEERING_TIMEOUT / 4;
    private long peerJobInterval = (long) (PEERING_TIMEOUT * 1.2);
    private long peeringTimeout = PEERING_TIMEOUT * 5;
    private long multicastEchoTimeout = PEERING_TIMEOUT / 2;

    private List<NetworkInterface> interfaceList = new CopyOnWriteArrayList<>();

    @Setter(PRIVATE)
    @Getter(PRIVATE)
    private ThreadFactory defaultThreadFactory = defaultThreadFactory();

    private ScheduledExecutorService scheduledThreadPool = newScheduledThreadPool(2, defaultThreadFactory);

    @Override
    public void launch() {
        init();
    }

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
        //работа с анонсами
        defaultThreadFactory.newThread(this::discoveryHandler).start();
        Scheduler.scheduleWithFixedDelaySafe(this::peerAnnounce, (long) (announceInterval * 1.2), MILLISECONDS);

        //обычные данные
        var peeringWait = announceInterval * 1.2;
        log.info("{}  discovering peers for {} seconds...", this.getInterfaceName(), MILLISECONDS.toSeconds((long) peeringWait));

        //Запускаем udp сервера на всех интерфейсам в своих потоках для слушания соединений
        defaultThreadFactory.newThread(this::initNetworkInterfaceServer).start();

        //запускаем peerJob, которая проверяет пиров, от которых давно не было анонсов
        Scheduler.scheduleWithFixedDelaySafe(this::peerJob, peerJobInterval, MILLISECONDS);

        online.set(true);
    }

    public String getMcastDiscoveryAddress() {
        if (nonNull(mcastDiscoveryAddress)) {
            return mcastDiscoveryAddress;
        } else {
            synchronized (this) {
                var groupHash = fullHash(getGroupId().getBytes(UTF_8));
                var sj = new StringJoiner(":")
                        .add("ff1" + discoveryScope.getScopeValue())
                        .add("0");
                for (int i = 2; i <= 12; i += 2) {
                    sj.add(format("%02x", toUnsignedInt(groupHash[i + 1]) + (toUnsignedInt(groupHash[i]) << 8)));
                }
                mcastDiscoveryAddress = sj.toString();
            }

            return mcastDiscoveryAddress;
        }
    }

    private void initNetworkInterfaceServer() {
        try (var socket = new DatagramSocket(dataPort)) {
            while (true) {
                byte[] buf = new byte[1024];
                var packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                processIncoming(Arrays.copyOf(packet.getData(), packet.getLength()));
            }
        } catch (SocketException e) {
            log.error("SocketException when init DatagramSocket", e);
            throw new RuntimeException(e);
        } catch (IOException ioException) {
            log.error("IOException when process incoming message", ioException);
        }
    }

    private void peerAnnounce() {
        try (var socket = new DatagramSocket()) {
            getLinkLocalAddresses()
                    .forEach(
                            inetAddress -> {
                                var localAddress = getLocalIpv6Address((Inet6Address) inetAddress);
                                var token = fullHash(
                                        concatArrays(
                                                getGroupId().getBytes(UTF_8),
                                                localAddress.getBytes(UTF_8)
                                        )
                                );
                                DatagramPacket packet = null;
                                try {
                                    packet = new DatagramPacket(
                                            token,
                                            token.length,
                                            InetAddress.getByName(getMcastDiscoveryAddress()),
                                            discoveryPort
                                    );
                                    socket.send(packet);
                                } catch (IOException e) {
                                    log.error("Error while send announce packet {} from address {}", packet, inetAddress, e);
                                }
                            }
                    );
        } catch (SocketException e) {
            log.error("Can not establish DatagramSocket to send announce", e);
        }
    }

    @SneakyThrows
    private void discoveryHandler() {
        var group = InetAddress.getByName(getMcastDiscoveryAddress());
        try (var discoverySocket = new MulticastSocket(discoveryPort)) {
            discoverySocket.joinGroup(group);
            var buf = new byte[1024];
            while (true) {
                var packet = new DatagramPacket(buf, buf.length);
                try {
                    discoverySocket.receive(packet);
                    var peerAddress = packet.getAddress();
                    if (getLinkLocalAddresses().contains(peerAddress)) {
                        continue;
                    }
                    var packetData = Arrays.copyOf(packet.getData(), packet.getLength());
                    var ipV6AddressString = new IPv6Address(packet.getAddress().getAddress()).toCompressedString();
                    var expectedHash = fullHash(concatArrays(getGroupId().getBytes(UTF_8), ipV6AddressString.getBytes(UTF_8)));
                    if (Arrays.equals(packetData, expectedHash)) {
                        addPeer(peerAddress);
                    } else {
                        log.debug(
                                "{} received peering packet on {} from {}, but authentication hash was incorrect.",
                                this, discoverySocket.getNetworkInterface().getName(), ipV6AddressString
                        );
                    }
                } catch (IOException e) {
                    log.error("Error while receive multicast packet {}", packet, e);
                }
            }
        } catch (IOException e) {
            log.error("Error while init multicast socket", e);
            throw new RuntimeException(e);
        }
    }

    private void addPeer(InetAddress peerAddress) {
        if (isFalse(peers.containsKey(peerAddress))) {
            log.debug("{} added peer {}", this, peerAddress);
        }
        peers.put(peerAddress, Instant.now());
    }

    @Override
    public synchronized void processIncoming(final byte[] data) {
        var dataHash = Hex.encodeHexString(fullHash(data));
        if (isFalse(mifDeque.contains(dataHash))) {
            while (mifDeque.size() >= MULTI_IF_DEQUE_LEN) {
                mifDeque.pop();
            }
            mifDeque.add(dataHash);
            rxb.updateAndGet(previous -> previous.add(BigInteger.valueOf(data.length)));
            Transport.getInstance().inbound(data, this);
        }
    }

    @Override
    public synchronized void processOutgoing(byte[] data) {
        for (InetAddress peerAddress : peers.keySet()) {
            try (var socket = new DatagramSocket()) {
                socket.send(new DatagramPacket(data, data.length, peerAddress, dataPort));
            } catch (IOException e) {
                log.error("Could not transmit on {}.", this, e);
            }
        }

        txb.updateAndGet(previous -> previous.add(BigInteger.valueOf(data.length)));
    }

    private List<InetAddress> getLinkLocalAddresses() {
        return interfaceList.stream()
                .map(this::getInet6Address)
                .filter(InetAddress::isLinkLocalAddress)
                .collect(toList());
    }

    private synchronized void peerJob() {
        //Check for timed out peers and remove any timed out peers
        peers.entrySet().stream()
                .filter(entry -> Duration.between(entry.getValue(), Instant.now()).toMillis() > peeringTimeout)
                .map(Map.Entry::getKey)
                .collect(toList())
                .forEach(
                        peerAddress -> {
                            peers.remove(peerAddress);
                            log.debug("{} removed peer {}", this, peerAddress);
                        }
                );

        try {
            //Check that the link-local address has not changed
            var newIfaceList = networkInterfaceList(this);

            var toRemove = CollectionUtils.subtract(interfaceList, newIfaceList);
            for (NetworkInterface iface : toRemove) {
                log.debug("Shutting down previous UDP listener for {} on {}", this, iface.getName());
                interfaceList.remove(iface);
            }

            var toAdd = CollectionUtils.subtract(newIfaceList, interfaceList);
            for (NetworkInterface iface : toAdd) {
                log.debug("Starting new UDP listener for {} {}", this, getInet6Address(iface));
                interfaceList.add(iface);
            }
        } catch (SocketException e) {
            log.error("Could not get device information while updating link-local addresses for {}.", this.getInterfaceName(), e);
        }
    }
}
