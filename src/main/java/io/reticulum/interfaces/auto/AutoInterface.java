package io.reticulum.interfaces.auto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.reticulum.interfaces.AbstractConnectionInterface;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.tuple.MutablePair;

import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.reticulum.interfaces.auto.AutoInterfaceConstant.BITRATE_GUESS;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.DEFAULT_DATA_PORT;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.DEFAULT_DISCOVERY_PORT;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.DEFAULT_IFAC_SIZE;
import static io.reticulum.interfaces.auto.AutoInterfaceConstant.PEERING_TIMEOUT;
import static io.reticulum.interfaces.auto.DiscoveryScope.SCOPE_LINK;
import static io.reticulum.utils.IdentityUtils.fullHash;
import static java.lang.Byte.toUnsignedInt;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.nonNull;
import static java.util.concurrent.Executors.defaultThreadFactory;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.apache.commons.lang3.BooleanUtils.isTrue;
import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;

@NoArgsConstructor
@ToString(callSuper = true)
@Slf4j
@Setter
@Getter
public class AutoInterface extends AbstractConnectionInterface implements AutoInterfaceUtil {

    {
        ifacSize = DEFAULT_IFAC_SIZE;
        configuredBitrate = BITRATE_GUESS;
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
    private Map<Peer, MutablePair<NetworkInterface, Long>> peers = new ConcurrentHashMap<>();
    private Map<NetworkInterface, Thread> interfaceServers = new ConcurrentHashMap<>();
    private Map<NetworkInterface, Long> multicastEchoes = new ConcurrentHashMap<>();
    private Map<NetworkInterface, Boolean> timedOutInterfaces = new ConcurrentHashMap<>();
    private AtomicBoolean carrierChanged = new AtomicBoolean(false);

    private String mcastDiscoveryAddress;
    private double announceInterval = PEERING_TIMEOUT / 6;
    private double peerJobInterval = PEERING_TIMEOUT * 1.1;
    private double peeringTimeout = PEERING_TIMEOUT;
    private double multicastEchoTimeout = PEERING_TIMEOUT / 2;
    private boolean receives;

    private List<NetworkInterface> interfaceList = new CopyOnWriteArrayList<>();

    @Setter(PRIVATE)
    @Getter(PRIVATE)
    private ScheduledExecutorService peerAnnounceScheduledExecutor;

    @Setter(PRIVATE)
    @Getter(PRIVATE)
    private ExecutorService multicastDiscoveryListenerExecutor;

    @Setter(PRIVATE)
    @Getter(PRIVATE)
    private ScheduledExecutorService peerJobScheduledExecutor = newSingleThreadScheduledExecutor();

    @Setter(PRIVATE)
    @Getter(PRIVATE)
    private ExecutorService cachedTreadPoolExecutor = newCachedThreadPool();

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
        interfaceList.addAll(networkInterfaceList(this));

        //создаем поток, в котором будут слушаться multicast discovery сообщения и добавляться в список пиров
        if (initMulticastDiscoveryListeners(interfaceList) && initPeerAnnounces(interfaceList)) {
            log.trace("{} could not autoconfigure. This interface currently provides no connectivity.", getInterfaceName());
        } else {
            receives = true;
            var peering_wait = secToMillisec(getAnnounceInterval() * 1.2);
            log.info("{}  discovering peers for {} seconds...", this.getInterfaceName(), MILLISECONDS.toSeconds(peering_wait));

            //Запускаем udp сервера на всех интерфейсам в своих потоках для слушания соединений
            initNetworkInterfaceServers(interfaceList);

            //запускаем peerJob, которая проверяет пиров, от которых давно не было анонсов и перезапускает udp сервер если там изменился адрес
            schedulePeerJob();

            online.set(true);
        }
    }

    public String getMcastDiscoveryAddress() {
        if (nonNull(mcastDiscoveryAddress)) {
            return mcastDiscoveryAddress;
        } else {
            synchronized (this) {
                var groupHash = fullHash(getGroupId().getBytes(UTF_8));
                var sj = new StringJoiner(":")
                        .add("ff1" + getDiscoveryScope().getScopeValue())
                        .add("0");
                for (int i = 2; i <= 12; i += 2) {
                    sj.add(format("%02x", toUnsignedInt(groupHash[i + 1]) + (toUnsignedInt(groupHash[i]) << 8)));
                }
                mcastDiscoveryAddress = sj.toString();
            }

            return mcastDiscoveryAddress;
        }
    }

    private boolean initPeerAnnounces(List<NetworkInterface> networkInterfaceList) throws InterruptedException {
        peerAnnounceScheduledExecutor = newScheduledThreadPool(networkInterfaceList.size());
        return networkInterfaceList.stream()
                .map(
                        iface -> peerAnnounceScheduledExecutor.scheduleAtFixedRate(
                                () -> peerAnnounce(iface),
                                secToMillisec(getAnnounceInterval() * 1.2),
                                secToMillisec(getAnnounceInterval()),
                                MILLISECONDS
                        )
                )
                .findAny()
                .isPresent();
    }

    private boolean initMulticastDiscoveryListeners(List<NetworkInterface> networkInterfaceList) throws InterruptedException {
        multicastDiscoveryListenerExecutor = newFixedThreadPool(networkInterfaceList.size());
        return networkInterfaceList.stream()
                .map(
                        iface -> multicastDiscoveryListenerExecutor.submit(
                                () -> discoveryHandler(initMulticastDiscoveryListener(iface))
                        )
                ).findAny().isPresent();
    }

    private void initNetworkInterfaceServers(List<NetworkInterface> networkInterfaceList) {
        for (NetworkInterface networkInterface : networkInterfaceList) {
            var thread = defaultThreadFactory().newThread(
                    () -> {
                        try (
                                var socket = new DatagramSocket(
                                        new InetSocketAddress(getInet6Address(networkInterface), getDataPort())
                                )
                        ) {
                            while (true) {
                                byte[] buf = new byte[1024];
                                var packet = new DatagramPacket(buf, buf.length);
                                socket.receive(packet);
                                processIncoming(packet.getData());
                            }
                        } catch (SocketException e) {
                            log.error("SocketException when init DatagramSocket", e);
                            throw new RuntimeException(e);
                        } catch (IOException ioException) {
                            log.error("IOException when process incoming message", ioException);
                            throw new RuntimeException(ioException);
                        }
                    }
            );
            thread.start();
            interfaceServers.put(networkInterface, thread);
        }
    }

    private void schedulePeerJob() {
        peerJobScheduledExecutor.schedule(
                this::peerJob,
                secToMillisec(getPeerJobInterval()),
                MILLISECONDS
        );
    }

    @SneakyThrows
    private MulticastSocket initMulticastDiscoveryListener(NetworkInterface networkInterface) {
        var multicastSocket = new MulticastSocket(getDiscoveryPort());
        var group = InetAddress.getByName(getMcastDiscoveryAddress() + "%" + networkInterface.getName());
        multicastSocket.joinGroup(group);

        return multicastSocket;
    }

    private void peerAnnounce(final NetworkInterface networkInterface) {
        var discoveryToken = fullHash(
                (getGroupId() + getLocalIpv6Address(networkInterface)).getBytes(UTF_8)
        );
        try (var multicastSocket = new MulticastSocket(getDiscoveryPort())) {
            multicastSocket.setNetworkInterface(networkInterface);
            var group = InetAddress.getByName(getMcastDiscoveryAddress());
            multicastSocket.joinGroup(group);
            multicastSocket.send(new DatagramPacket(discoveryToken, discoveryToken.length, group, getDiscoveryPort()));
            multicastSocket.leaveGroup(group);
        } catch (IOException e) {
            if (
                    (timedOutInterfaces.containsKey(networkInterface) && isFalse(timedOutInterfaces.get(networkInterface)))
                    || isFalse(timedOutInterfaces.containsKey(networkInterface))
            ) {
                log.warn("{}  Detected possible carrier loss on {}.", this.getInterfaceName(), networkInterface.getName(), e);
            }

            log.error("Error while send announce on interface {}", networkInterface, e);
        }
    }

    private void discoveryHandler(MulticastSocket multicastSocket) {
        while (true) {
            var buf = new byte[1024];
            var packet = new DatagramPacket(buf, buf.length);
            try {
                multicastSocket.receive(packet);
                var peerAddress = packet.getAddress();
                var ipV6Address = peerAddress.getHostAddress().split("&")[0];
                var expectedHash = fullHash((getGroupId() + ipV6Address).getBytes(UTF_8));
                if (Arrays.equals(packet.getData(), expectedHash)) {
                    addPeer(new Peer(peerAddress, packet.getPort()), multicastSocket.getNetworkInterface());
                } else {
                    log.debug(
                            "{} received peering packet on {}  from {}, but authentication hash was incorrect.",
                            this.getInterfaceName(), multicastSocket.getNetworkInterface().getName(), ipV6Address
                    );
                }
            } catch (IOException e) {
                log.error("Error while receive multicast packet {}", packet, e);
            }
        }
    }

    private void addPeer(Peer peer, NetworkInterface networkInterface) {
        var currentTime = currentTimeMillis();
        if (getLinkLocalAddresses().contains(peer.getAddress())) {
            getInterfaceList().stream()
                    .filter(iface -> iface.inetAddresses().anyMatch(address -> address.equals(peer.getAddress())))
                    .findFirst()
                    .ifPresentOrElse(
                            iface -> multicastEchoes.put(iface, currentTime),
                            () -> log.warn("{} received multicast echo on unexpected interface {}", this.getInterfaceName(), networkInterface.getName())
                    );
        } else {
            if (isFalse(peers.containsKey(peer))) {
                peers.put(peer, MutablePair.of(networkInterface, currentTime));
                log.debug("{} added peer {} on {}", this.getInterfaceName(), peer, networkInterface);
            } else {
                peers.get(peer).setRight(currentTime);
            }
        }
    }

    public void processIncoming(final byte[] data) {
        rxb.updateAndGet(previous -> previous.add(BigInteger.valueOf(data.length)));
        transport.inbound(data);
    }

    @Override
    public void processOutgoing(byte[] data) {
        //pass
    }

    private void processOutgoung(final byte[] data) {
        for (Peer peer : peers.keySet()) {
            try(var socket = new DatagramSocket(getDataPort())) {
                var packet = new DatagramPacket(data, data.length, peer.getAddress(), peer.getPort());
                socket.send(packet);
            } catch (IOException e) {
                log.error("Could not transmit on {}.", this.getInterfaceName(), e);
            }
        }
        txb.updateAndGet(previous -> previous.add(BigInteger.valueOf(data.length)));
    }

    private List<InetAddress> getLinkLocalAddresses() {
        return getInterfaceList().stream()
                .map(this::getInet6Address)
                .collect(toList());
    }

    private void peerJob() {
        //Check for timed out peers and remove any timed out peers
        peers.entrySet().stream()
                .filter(entry -> currentTimeMillis() > (entry.getValue().getRight() + secToMillisec(getPeeringTimeout())))
                .map(Map.Entry::getKey)
                .collect(toList())
                .forEach(
                        peerAddress -> {
                            var removed = peers.remove(peerAddress);
                            log.debug("{} removed peer {} on {}", this.getInterfaceName(), peerAddress, removed.getLeft().getName());
                        }
                );

        try {
            //Check that the link-local address has not changed
            var newIfaceList = networkInterfaceList(this);

            var toRemove = CollectionUtils.subtract(interfaceList, newIfaceList);
            for (NetworkInterface iface : toRemove) {
                log.debug("Shutting down previous UDP listener for {} on {}", this.getInterfaceName(), iface.getName());
                interfaceList.remove(iface);
                var thread = interfaceServers.remove(iface);
                cachedTreadPoolExecutor.submit(thread::interrupt);
            }

            var toAdd = CollectionUtils.subtract(newIfaceList, interfaceList);
            for (NetworkInterface iface : toAdd) {
                log.debug("Starting new UDP listener for {} {}", this.getInterfaceName(), iface.getName());
                interfaceList.add(iface);
                initNetworkInterfaceServers(List.of(iface));
            }

            //Check multicast echo timeouts
            for (NetworkInterface iface : interfaceList) {
                var lastMulticastEcho = multicastEchoes.getOrDefault(iface, 0L);
                if ((System.currentTimeMillis() - lastMulticastEcho) > secToMillisec(getMulticastEchoTimeout())) {
                    if (timedOutInterfaces.containsKey(iface) && isFalse(timedOutInterfaces.get(iface))) {
                        carrierChanged.set(true);
                        log.warn("Multicast echo timeout for {}. Carrier lost.", iface.getName());
                    }
                    timedOutInterfaces.put(iface, true);
                } else {
                    if (timedOutInterfaces.containsKey(iface) && isTrue(timedOutInterfaces.get(iface))) {
                        carrierChanged.set(true);
                        log.warn("{}  Carrier recovered on {}", this.getInterfaceName(), iface.getName());
                    }
                    timedOutInterfaces.put(iface, false);
                }
            }
        } catch (SocketException e) {
            log.error("Could not get device information while updating link-local addresses for {}.", this.getInterfaceName(), e);
        }
    }
}
