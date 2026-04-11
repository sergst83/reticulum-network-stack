package io.reticulum.interfaces.backbone;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.reticulum.interfaces.AbstractConnectionInterface;
import io.reticulum.interfaces.HDLC;
import io.reticulum.interfaces.InterfaceMode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * BackboneServerInterface — the listener/server side of a Reticulum Backbone interface.
 *
 * <p>Corresponds to {@code BackboneInterface} in the Python reference implementation
 * (RNS/Interfaces/BackboneInterface.py).
 *
 * <p>On Linux, Netty's epoll transport ({@link EpollEventLoopGroup} +
 * {@link EpollServerSocketChannel}) is used automatically when available, providing
 * the same scalability benefit as Python's {@code select.epoll}.  On other platforms
 * the implementation falls back to NIO ({@link NioEventLoopGroup} +
 * {@link NioServerSocketChannel}), matching the Python fall-back to TCPServerInterface.
 *
 * <p>Configuration keys (YAML):
 * <ul>
 *   <li>{@code listen_ip}   — IP address to bind to (default {@code "0.0.0.0"})</li>
 *   <li>{@code listen_port} — TCP port to listen on (required)</li>
 *   <li>{@code prefer_ipv6} — prefer IPv6 when resolving addresses (default {@code false})</li>
 * </ul>
 * Discovery-related configuration keys:
 * <ul>
 *   <li>{@code discoverable}                — advertise this interface on the network (default {@code false})</li>
 *   <li>{@code discovery_name}              — human-readable name to advertise</li>
 *   <li>{@code reachable_on}                — IP/hostname that remote nodes should connect to</li>
 *   <li>{@code discovery_announce_interval} — seconds between discovery announces (default 300)</li>
 *   <li>{@code discovery_stamp_value}       — proof-of-work difficulty (default 14)</li>
 *   <li>{@code discovery_encrypt}           — encrypt discovery payload (default {@code false})</li>
 *   <li>{@code discovery_publish_ifac}      — include IFAC credentials in discovery announce (default {@code false})</li>
 * </ul>
 */
@Slf4j
@Getter
@Setter
public class BackboneServerInterface extends AbstractConnectionInterface implements HDLC {

    /** Maximum hardware MTU – 1 MiB, matching Python's {@code HW_MTU}. */
    public static final int HW_MTU = 1_048_576;

    private static final int BITRATE_GUESS    = 1_000_000_000; // 1 Gbit/s
    private static final int DEFAULT_IFAC_SIZE = 16;

    // ── Network binding ──────────────────────────────────────────────────────

    @JsonProperty("listen_ip")
    private String listenIp = "0.0.0.0";

    @JsonProperty("listen_port")
    private int listenPort;

    @JsonProperty("prefer_ipv6")
    private boolean preferIpv6 = false;

    // ── Runtime state ────────────────────────────────────────────────────────

    private volatile boolean detached = false;
    private ChannelFuture channelFuture;

    /** Spawned client interfaces, one per accepted TCP connection. */
    protected List<BackboneClientInterface> spawnedInterfaces = new CopyOnWriteArrayList<>();

    // ── Discovery ────────────────────────────────────────────────────────────

    /** Whether the interface type supports on-network discovery. Always {@code true}. */
    private final boolean supportsDiscovery = true;

    /** Whether this instance is configured to actively announce itself. */
    @JsonProperty("discoverable")
    private boolean discoverable = false;

    /** Human-readable name to include in discovery announces. */
    @JsonProperty("discovery_name")
    private String discoveryName;

    /**
     * IP address or hostname that remote nodes should use to reach this server.
     * Can be a literal value or the path to an executable whose stdout provides
     * the value (non-Windows only, matching Python behaviour).
     */
    @JsonProperty("reachable_on")
    private String reachableOn;

    /** GPS latitude for the discovery announce (optional, default 0.0). */
    @JsonProperty("discovery_latitude")
    private double discoveryLatitude = 0.0;

    /** GPS longitude for the discovery announce (optional, default 0.0). */
    @JsonProperty("discovery_longitude")
    private double discoveryLongitude = 0.0;

    /** Altitude in metres for the discovery announce (optional, default 0.0). */
    @JsonProperty("discovery_height")
    private double discoveryHeight = 0.0;

    /** Proof-of-work difficulty for discovery announces (default 14, matching Python). */
    @JsonProperty("discovery_stamp_value")
    private int discoveryStampValue = 14;

    /** Interval between discovery announces in seconds (default 300). */
    @JsonProperty("discovery_announce_interval")
    private int discoveryAnnounceInterval = 300;

    /** Epoch-second timestamp of the last sent discovery announce. */
    private long lastDiscoveryAnnounce = 0;

    /** Whether to encrypt the discovery payload (requires a network identity). */
    @JsonProperty("discovery_encrypt")
    private boolean discoveryEncrypt = false;

    /** Whether to include IFAC network name / passphrase in the discovery announce. */
    @JsonProperty("discovery_publish_ifac")
    private boolean discoveryPublishIfac = false;

    // ── Construction ─────────────────────────────────────────────────────────

    public BackboneServerInterface() {
        super();
        this.rxb.set(BigInteger.ZERO);
        this.txb.set(BigInteger.ZERO);

        this.IN  = true;
        this.OUT = false;
        this.interfaceMode = InterfaceMode.MODE_FULL;
        this.bitrate = BITRATE_GUESS;

        if (isNull(ifacSize)) {
            ifacSize = DEFAULT_IFAC_SIZE;
        }
    }

    // ── Thread / lifecycle ───────────────────────────────────────────────────

    @Override
    public void run() {
        try {
            startListening();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void launch() {
        start();
    }

    /**
     * Binds and starts the TCP server.
     *
     * <p>Uses epoll when running on Linux (Netty's {@link Epoll#isAvailable()} is
     * {@code true}), otherwise falls back to NIO selectors — matching the Python
     * reference which uses {@code select.epoll} on Linux and falls back to
     * {@code TCPServerInterface} on other platforms.
     */
    public void startListening() throws InterruptedException {
        EventLoopGroup bossGroup   = BackboneTransport.newBossGroup();
        EventLoopGroup workerGroup = BackboneTransport.newWorkerGroup();

        log.debug("BackboneServerInterface using {} for {}",
                BackboneTransport.EPOLL_USABLE ? "Linux epoll" : "NIO selector", this);

        var bootstrap = new ServerBootstrap();
        bootstrap
                .group(bossGroup, workerGroup)
                .channel(BackboneTransport.serverChannelClass())
                .option(ChannelOption.SO_BACKLOG,  1024)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new BackboneChannelInitializer(this));

        var bindAddress = (listenIp != null && !listenIp.isEmpty())
                ? new InetSocketAddress(listenIp, listenPort)
                : new InetSocketAddress(listenPort);

        this.channelFuture = bootstrap.bind(bindAddress)
                .addListener((ChannelFutureListener) future ->
                        future.channel().closeFuture()
                                .addListener((ChannelFutureListener) closeFeature -> {
                                    bossGroup.shutdownGracefully();
                                    workerGroup.shutdownGracefully();
                                    online.set(false);
                                })
                ).sync();

        online.set(true);
        log.info("BackboneServerInterface [{}] listening on {}:{}", interfaceName, listenIp, listenPort);
    }

    // ── Data plane ───────────────────────────────────────────────────────────

    @Override
    public synchronized void processIncoming(byte[] data) {
        var processingData = unmaskHdlc(data);
        rxb.accumulateAndGet(BigInteger.valueOf(processingData.length), BigInteger::add);
        // Inbound data is dispatched by the spawned BackboneClientInterface; this
        // method is kept for completeness but is not called in the normal path.
    }

    /**
     * The server does not send directly; outgoing packets are dispatched by each
     * spawned {@link BackboneClientInterface}.
     */
    @Override
    public synchronized void processOutgoing(byte[] data) {
        // pass — handled by spawned interfaces
    }

    // ── Shutdown ─────────────────────────────────────────────────────────────

    @Override
    public synchronized void detach() {
        if (nonNull(channelFuture) && channelFuture.channel().isActive()) {
            log.debug("Detaching {}", this);
            detached = true;
            try {
                channelFuture.channel().close();
            } catch (Exception e) {
                log.error("Error while shutting down channel for {}", this, e);
            }
            channelFuture = null;
        }
    }

    // ── Announce tracking ────────────────────────────────────────────────────

    @Override
    public void sentAnnounce(boolean fromSpawned) {
        if (fromSpawned) {
            oaFreqDeque.add(0, Instant.now());
        }
    }

    @Override
    public void receivedAnnounce(boolean fromSpawned) {
        if (fromSpawned) {
            iaFreqDeque.add(0, Instant.now());
        }
    }

    // ── Object identity ──────────────────────────────────────────────────────

    @Override
    public String toString() {
        return getInterfaceName() + "/" + listenIp + ":" + listenPort;
    }
}
