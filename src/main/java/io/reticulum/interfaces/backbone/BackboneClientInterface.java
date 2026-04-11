package io.reticulum.interfaces.backbone;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundInvoker;
import io.netty.channel.EventLoopGroup;
import io.reticulum.Transport;
import io.reticulum.interfaces.AbstractConnectionInterface;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.interfaces.HDLC;
import io.reticulum.interfaces.InterfaceMode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

/**
 * BackboneClientInterface — a single TCP connection inside a Backbone transport.
 *
 * <p>This class plays two distinct roles:
 * <ol>
 *   <li><b>Initiator (client mode)</b> — created directly from configuration with
 *       {@code target_host} / {@code target_port}; connects to a remote
 *       {@link BackboneServerInterface} and reconnects automatically on disconnect.</li>
 *   <li><b>Spawned (server side)</b> — created by {@link BackboneChannelInitializer}
 *       for every incoming connection accepted by a {@link BackboneServerInterface};
 *       identified by {@code initiator == false}.</li>
 * </ol>
 *
 * <p>Corresponds to {@code BackboneClientInterface} in the Python reference
 * implementation (RNS/Interfaces/BackboneInterface.py).
 *
 * <p>On Linux, Netty's epoll transport is used automatically when available.
 * On other platforms the implementation falls back to NIO selectors.
 *
 * <p>HDLC framing (FLAG + escaped payload + FLAG) is used, identical to
 * the TCP interfaces.
 *
 * <p>Configuration keys (YAML, client mode only):
 * <ul>
 *   <li>{@code target_host}        — remote hostname or IP</li>
 *   <li>{@code target_port}        — remote TCP port</li>
 *   <li>{@code connect_timeout}    — initial connect timeout in milliseconds (default 5 000)</li>
 *   <li>{@code max_reconnect_tries}— max reconnect attempts; {@code null} = unlimited (default)</li>
 *   <li>{@code prefer_ipv6}        — prefer IPv6 (default {@code false})</li>
 *   <li>{@code i2p_tunneled}       — mark as I2P-tunnelled (default {@code false})</li>
 * </ul>
 */
@Slf4j
@Getter
@Setter
public class BackboneClientInterface extends AbstractConnectionInterface implements HDLC {

    private static final int  BITRATE_GUESS          = 100_000_000; // 100 Mbit/s
    private static final long INITIAL_CONNECT_TIMEOUT = 5_000;       // ms
    private static final long RECONNECT_WAIT          = 5;            // seconds

    // ── Configuration (client / initiator mode) ──────────────────────────────

    @JsonProperty("target_host")
    private String targetHost;

    @JsonProperty("target_port")
    private int targetPort;

    @JsonProperty("prefer_ipv6")
    private boolean preferIpv6 = false;

    @JsonProperty("i2p_tunneled")
    private boolean i2pTunneled = false;

    @JsonProperty("connect_timeout")
    private long connectTimeout = INITIAL_CONNECT_TIMEOUT;

    /**
     * Maximum reconnect attempts before tearing down; {@code null} means unlimited
     * (matching Python's {@code RECONNECT_MAX_TRIES = None}).
     */
    @JsonProperty("max_reconnect_tries")
    private Integer maxReconnectTries = null;

    // ── Runtime state ────────────────────────────────────────────────────────

    private Timer timer;
    private ChannelFuture channelFuture;
    private Channel channel;

    /** {@code true} when this interface originated the connection. */
    private boolean initiator;

    private volatile boolean reconnecting  = false;
    private volatile boolean neverConnected = true;
    private volatile boolean detached      = false;

    /** Set for spawned interfaces created by a {@link BackboneServerInterface}. */
    private BackboneServerInterface parentInterface;

    // ── Construction ─────────────────────────────────────────────────────────

    public BackboneClientInterface() {
        super();
        this.initiator = true;
        this.rxb.set(BigInteger.ZERO);
        this.txb.set(BigInteger.ZERO);

        this.IN  = true;
        this.OUT = false;
        this.interfaceMode = InterfaceMode.MODE_FULL;
        this.bitrate = BITRATE_GUESS;

        if (isNull(ifacSize)) {
            ifacSize = 16;
        }

        timer = new Timer(true); // daemon timer
    }

    /**
     * Constructor for spawned interfaces (accepted connections on the server side).
     *
     * @param name       interface name, e.g. {@code "Client on BackboneServerInterface[…]"}
     * @param channel    the already-connected Netty channel
     * @param i2pTunneled whether the parent server is I2P-tunnelled
     */
    public BackboneClientInterface(String name, Channel channel, boolean i2pTunneled) {
        this();
        this.channel    = channel;
        this.initiator  = false;
        this.interfaceName = name;
        this.maxReconnectTries = 0;
        this.i2pTunneled = i2pTunneled;

        var remoteAddress = (InetSocketAddress) channel.remoteAddress();
        targetHost = remoteAddress.getAddress().getHostAddress();
        targetPort = remoteAddress.getPort();
    }

    // ── Thread / lifecycle ───────────────────────────────────────────────────

    @Override
    public void run() {
        try {
            connect(initiator);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void launch() {
        start();
    }

    // ── Data plane ───────────────────────────────────────────────────────────

    @Override
    public synchronized void processIncoming(byte[] data) {
        var processingData = unmaskHdlc(data);
        rxb.accumulateAndGet(BigInteger.valueOf(processingData.length), BigInteger::add);

        if (nonNull(parentInterface)) {
            parentInterface.getRxb()
                    .accumulateAndGet(BigInteger.valueOf(processingData.length), BigInteger::add);
        }

        Transport.getInstance().inbound(processingData, this);
    }

    @Override
    public synchronized void processOutgoing(byte[] data) {
        log.trace("Backbone send. interface: {}", this);
        if (online.get()) {
            try (var os = new ByteArrayOutputStream()) {
                os.write(FLAG);
                os.write(escapeHdlc(data));
                os.write(FLAG);

                getInternalChannel()
                        .map(ch -> ch.writeAndFlush(os.toByteArray()))
                        .orElseThrow(() -> new RuntimeException("Channel not present for " + this));

                txb.accumulateAndGet(BigInteger.valueOf(data.length), BigInteger::add);

                if (nonNull(parentInterface)) {
                    parentInterface.getTxb()
                            .accumulateAndGet(BigInteger.valueOf(data.length), BigInteger::add);
                }
            } catch (Exception e) {
                log.error("Exception while transmitting via {}, tearing down interface.", this, e);
                teardown();
            }
        }
    }

    // ── Connection management ────────────────────────────────────────────────

    /**
     * Opens (or re-opens) the TCP connection.
     *
     * <p>Uses epoll on Linux when available, falls back to NIO otherwise.
     *
     * @param initial {@code true} for the first connection attempt (controls error verbosity)
     * @return {@code true} if the connection was established successfully
     */
    private synchronized boolean connect(final Boolean initial) throws InterruptedException {
        boolean init       = BooleanUtils.isTrue(initial);
        EventLoopGroup workerGroup = BackboneTransport.newWorkerGroup();

        try {
            if (init) {
                log.debug("Establishing Backbone TCP connection for {} …", this);
            }

            var bootstrap = new Bootstrap();
            bootstrap
                    .group(workerGroup)
                    .channel(BackboneTransport.socketChannelClass())
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY,  true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout)
                    .handler(new BackboneChannelInitializer(this));

            this.channelFuture = bootstrap.connect(targetHost, targetPort)
                    .addListener((ChannelFutureListener) future ->
                            future.channel().closeFuture()
                                    .addListener((ChannelFutureListener) closeFeature -> {
                                        online.set(false);
                                        if (isFalse(detached)) {
                                            startReconnecting();
                                        } else {
                                            workerGroup.shutdownGracefully();
                                        }
                                    })
                    ).sync();

            online.set(channelFuture.channel().isActive());
            neverConnected = false;
            log.debug("Backbone TCP connection for {} established.", this);

        } catch (Exception e) {
            if (init) {
                log.error("Initial connection for {} could not be established: {}", this, e.getMessage());
                log.error("Leaving unconnected and retrying connection in {} seconds.", RECONNECT_WAIT);
                return online.get();
            } else {
                throw e;
            }
        }

        return online.get();
    }

    private void startReconnecting() {
        timer.schedule(new TimerTask() {
            int attempts = 0;

            @Override
            public void run() {
                if (!initiator) {
                    log.error("Attempt to reconnect on a non-initiator Backbone interface. This should not happen.");
                    cancel();
                    return;
                }

                if (online.get()) {
                    cancel();
                    return;
                }

                if (isFalse(reconnecting)) {
                    reconnecting = true;
                } else {
                    if (maxReconnectTries != null && attempts > maxReconnectTries) {
                        log.error("Max reconnection attempts reached for {}", BackboneClientInterface.this);
                        teardown();
                        cancel();
                        return;
                    }
                    attempts++;
                    doReconnect(attempts);
                }
            }
        }, 500, Duration.ofSeconds(RECONNECT_WAIT).toMillis());
    }

    private synchronized void doReconnect(int currentAttempt) {
        try {
            reconnecting = !connect(false);
            if (isFalse(neverConnected) && online.get()) {
                log.info("Reconnected socket for {}", this);
            }
            Transport.getInstance().synthesizeTunnel(this);
        } catch (Exception e) {
            log.debug("Connection attempt {} for {} failed: {}", currentAttempt, this, e.getMessage());
        }
    }

    // ── Detach / teardown ────────────────────────────────────────────────────

    @Override
    public synchronized void detach() {
        var ch = getInternalChannel();
        if (ch.map(Channel::isActive).orElse(false)) {
            log.debug("Detaching {}", this);
            detached = true;
            try {
                ch.ifPresent(ChannelOutboundInvoker::close);
            } catch (Exception e) {
                log.error("Error while shutting down channel for {}", this, e);
            }
            this.channelFuture = null;
            this.channel = null;
        }
    }

    private void teardown() {
        if (initiator && isFalse(detached)) {
            log.error("The interface {} experienced an unrecoverable error and is being torn down. "
                    + "Restart Reticulum to attempt to open this interface again.", this);
            if (Transport.getInstance().getOwner().isPanicOnIntefaceError()) {
                System.exit(255);
            }
        } else {
            log.debug("The interface {} is being torn down.", this);
        }

        online.set(false);
        OUT = false;
        IN  = false;

        if (nonNull(parentInterface)) {
            parentInterface.getClients().decrementAndGet();
            parentInterface.spawnedInterfaces.remove(this);
        }

        if (Transport.getInstance().getInterfaces().contains(this) && isFalse(initiator)) {
            Transport.getInstance().getInterfaces().remove(this);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @Override
    public ConnectionInterface getParentInterface() {
        return parentInterface;
    }

    private Optional<Channel> getInternalChannel() {
        return Optional.ofNullable(channelFuture)
                .map(future -> {
                    try {
                        return future.sync().channel();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                })
                .or(() -> Optional.ofNullable(channel));
    }

    // ── Object identity ──────────────────────────────────────────────────────

    @Override
    public String toString() {
        return getInterfaceName() + "/" + targetHost + ":" + targetPort;
    }
}