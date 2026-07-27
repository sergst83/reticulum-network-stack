package io.reticulum.interfaces.tcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundInvoker;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.reticulum.Transport;
import io.reticulum.interfaces.AbstractConnectionInterface;
import io.reticulum.interfaces.HDLC;
import io.reticulum.interfaces.InterfaceMode;
import io.reticulum.interfaces.KISS;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNullElse;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

@Slf4j
@Getter
@Setter
public class TCPClientInterface extends AbstractConnectionInterface implements HDLC, KISS {

    private Timer timer;

    private static final int BITRATE_GUESS = 10_000_000;
    private static final long INITIAL_CONNECT_TIMEOUT = 5_000; //milliseconds
    private static final long RECONNECT_WAIT = 5; //seconds (initial backoff)
    private static final long MAX_RECONNECT_WAIT = 60; //seconds (backoff cap)
    private static final long CONNECT_ERROR_LOG_INTERVAL_MILLIS = 60_000; //throttle refused-connection logging

    // Guards against spawning more than one reconnect cycle per interface. Without it the
    // channel close-listener scheduled a fresh recurring timer on every failed connect,
    // multiplying into a reconnect storm (hundreds of attempts/sec) that starved the node.
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private volatile int reconnectAttempts = 0;
    private volatile long reconnectBackoffSeconds = RECONNECT_WAIT;
    private volatile long lastConnectErrorLogMillis = 0;

    private ChannelFuture channelFuture;
    private Channel channel;

    private Integer maxReconnectTries = 20;

    private boolean initiator;
    private volatile boolean neverConnected = true;
    private volatile boolean detached = false;

    private TCPServerInterface parentInterface;
    @JsonProperty("kiss_framing")
    private boolean kissFraming = false;
    @JsonProperty("i2p_tunneled")
    private boolean i2pTunneled = false;
    @JsonProperty("connect_timeout")
    private long connectionTimeout = INITIAL_CONNECT_TIMEOUT;
    @JsonProperty("target_host")
    private String targetHost;
    @JsonProperty("target_port")
    private int targetPort;

    public TCPClientInterface() {
        super();
        this.initiator = true;
        this.rxb.set(BigInteger.ZERO);
        this.txb.set(BigInteger.ZERO);

        this.IN = true;

        this.interfaceMode = InterfaceMode.MODE_FULL;
        this.bitrate = BITRATE_GUESS;

        if (isNull(ifacSize)) {
            ifacSize = 16;
        }

        timer = new Timer();
    }

    /**
     * A constructor for creating an interface for a return channel to the client connected to the server
     *
     * @param name interface name
     * @param channel channel for sending data to the client
     * @param i2pTunneled if tunneled
     */
    public TCPClientInterface(
            String name,
            Channel channel,
            Boolean i2pTunneled
    ) {
        this();
        this.channel = channel;
        this.initiator = false;
        this.interfaceName = name;
        this.maxReconnectTries = requireNonNullElse(maxReconnectTries, 0);

        if (nonNull(i2pTunneled)) {
            this.i2pTunneled = i2pTunneled;
        }

        //for toString
        var remoteAddress = (InetSocketAddress) channel.remoteAddress();
        targetHost = remoteAddress.getAddress().getHostAddress();
        targetPort = remoteAddress.getPort();
    }

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

    @Override
    public void processIncoming(byte[] data) {
        var processingData = kissFraming ? unmaskKiss(data) : unmaskHdlc(data);
        this.rxb.accumulateAndGet(BigInteger.valueOf(processingData.length), BigInteger::add);
        if (nonNull(parentInterface)) {
            ((AbstractConnectionInterface) parentInterface).getRxb()
                    .accumulateAndGet(BigInteger.valueOf(processingData.length), BigInteger::add);
        }

        Transport.getInstance().inbound(processingData, this);
    }

    @Override
    public void processOutgoing(byte[] data) {
        log.trace("Send packet data. interface: {}, message: {}", this, data);
        if (online.get()) {
            try(var os = new ByteArrayOutputStream()) {
                if (kissFraming) {
                    os.write(FEND);
                    os.write(CMD_DATA);
                    os.write(escapeKiss(data));
                    os.write(FEND);
                } else {
                    os.write(FLAG);
                    os.write(escapeHdlc(data));
                    os.write(FLAG);
                }

                getChannel()
                        .map(ch -> ch.writeAndFlush(os.toByteArray()))
                        .orElseThrow(() -> new RuntimeException("Channel is not present."));

                txb.accumulateAndGet(BigInteger.valueOf(data.length), BigInteger::add);
                if (nonNull(parentInterface)) {
                    ((AbstractConnectionInterface) parentInterface).getTxb()
                            .accumulateAndGet(BigInteger.valueOf(data.length), BigInteger::add);
                }
            } catch (Exception e) {
                log.error("Exception occurred while transmitting via {}, tearing down interface.", this, e);
                teardown();
            }
        }
    }

    private void startReconnecting() {
        if (isFalse(initiator)) {
            log.error("Attempt to reconnect on a non-initiator TCP interface. This should not happen");
            return;
        }
        // Idempotent: only one reconnect cycle may run per interface at a time. Re-entrant calls
        // from the close-listener (which fires on every failed connect) return immediately here,
        // so retries can no longer multiply.
        if (isFalse(reconnectScheduled.compareAndSet(false, true))) {
            return;
        }
        reconnectAttempts = 0;
        reconnectBackoffSeconds = RECONNECT_WAIT;
        scheduleReconnect(500);
    }

    /**
     * Schedules a single one-shot reconnect attempt {@code delayMillis} from now. Each attempt
     * reschedules the next with an exponentially increasing, capped backoff — so a persistently
     * refused target settles at one attempt per {@link #MAX_RECONNECT_WAIT} seconds instead of a
     * fixed-interval storm.
     */
    private void scheduleReconnect(long delayMillis) {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    if (online.get()) {
                        stopReconnecting();
                        return;
                    }
                    if (reconnectAttempts >= maxReconnectTries) {
                        log.error("Max reconnection attempts ({}) reached for {}, tearing down interface",
                                maxReconnectTries, TCPClientInterface.this);
                        stopReconnecting();
                        teardown();
                        return;
                    }
                    reconnectAttempts++;
                    reconnect(reconnectAttempts);
                    if (online.get()) {
                        log.info("Reconnected {} after {} attempt(s)", TCPClientInterface.this, reconnectAttempts);
                        stopReconnecting();
                        return;
                    }
                    // Still down: back off (capped) and try again.
                    reconnectBackoffSeconds = Math.min(reconnectBackoffSeconds * 2, MAX_RECONNECT_WAIT);
                    scheduleReconnect(Duration.ofSeconds(reconnectBackoffSeconds).toMillis());
                } catch (Exception e) {
                    // Never let a task throw: an uncaught exception kills the Timer thread and
                    // permanently stops all future reconnects for this interface.
                    log.debug("Reconnect task for {} failed, will retry", TCPClientInterface.this, e);
                    reconnectBackoffSeconds = Math.min(reconnectBackoffSeconds * 2, MAX_RECONNECT_WAIT);
                    scheduleReconnect(Duration.ofSeconds(reconnectBackoffSeconds).toMillis());
                }
            }
        }, delayMillis);
    }

    private void stopReconnecting() {
        reconnectScheduled.set(false);
    }

    private synchronized void reconnect(final int currentAttempt) {
        try {
            connect(initiator);
            if (isFalse(neverConnected) && online.get()) {
                log.info("Reconnected socket for {}", this);
            }
            // Ensure the interface is still registered with Transport after reconnect.
            if (!Transport.getInstance().getInterfaces().contains(this)) {
                Transport.getInstance().getInterfaces().add(this);
            }
            if (isFalse(kissFraming)) {
                Transport.getInstance().synthesizeTunnel(this);
            }
        } catch (Exception e) {
            log.debug("Connection attempt for {}  failed.", currentAttempt, e);
        }
    }

    private synchronized boolean connect(final Boolean initial) throws InterruptedException {
        var init = BooleanUtils.isTrue(initial);
        var self = this;
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            if (init) {
                log.debug("Establishing TCP connection for {} ...", this);
            }
            Bootstrap bootstrap = new Bootstrap();
            bootstrap
                    .group(workerGroup).channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new TCPChannelInitializer(self, kissFraming));

            // Start the client.
            this.channelFuture = bootstrap.connect(targetHost, targetPort)
                    .addListener(
                            (ChannelFutureListener) future -> future.channel().closeFuture()
                            .addListener((ChannelFutureListener) closeFeature -> {
                                //Listen close detect listener
                                online.set(false);
                                // Always release this connection's EventLoopGroup once the
                                // channel closes. Previously it was only shut down on the
                                // detached path, so every reconnect leaked a whole
                                // NioEventLoopGroup (~2×cores threads) — the nioEventLoopGroup
                                // thread pile-up seen in Qortal test-14. A reconnect creates a
                                // fresh group via connect(), so the old one is safe to drop.
                                workerGroup.shutdownGracefully();
                                if (isFalse(detached)) {
                                    startReconnecting();
                                }
                            })
                    ).sync();

            online.set(channelFuture.channel().isActive());
            neverConnected = false;
            log.debug("TCP connection for {} established", this);
        } catch (Exception e) {
            // Connection never became active, so its close-listener won't fire — release the
            // EventLoopGroup here to avoid leaking it on every failed connection attempt.
            workerGroup.shutdownGracefully();
            if (init) {
                // Throttle logging: a persistently refused target (e.g. a down gateway) would
                // otherwise log a full stack trace on every attempt — 25,900 stacks / 45s / 43MB
                // in test-15. Log a concise line at most once per interval; rest at DEBUG.
                long now = System.currentTimeMillis();
                if (now - lastConnectErrorLogMillis >= CONNECT_ERROR_LOG_INTERVAL_MILLIS) {
                    lastConnectErrorLogMillis = now;
                    log.error("Connection for {} could not be established: {}. Retrying with backoff (up to {}s).",
                            this, e.getMessage(), MAX_RECONNECT_WAIT);
                } else {
                    log.debug("Connection for {} still failing: {}", this, e.getMessage());
                }
                return online.get();
            } else {
                throw e;
            }
        }

        return online.get();
    }

    @Override
    public synchronized void detach() {
        var channel = getChannel();
        if (channel.map(Channel::isActive).orElse(false)) {
            log.debug("Detaching {}", this);
            detached = true;

            try {
                channel.ifPresent(ChannelOutboundInvoker::close);
            } catch (Exception e) {
                log.error("Error while shutting down channel for {}", this, e);
            }

            this.channelFuture = null;
            this.channel = null;
        }
    }

    @Override
    public String toString() {
        return getInterfaceName() + "/" + targetHost + ":" + targetPort;
    }

    private void teardown() {
        if (initiator && isFalse(detached)) {
            log.error("The interface {} experienced an unrecoverable error and is being torn down. Restart Reticulum to attempt to open this interface again.", this);
            if (Transport.getInstance().getOwner().isPanicOnIntefaceError()) {
                System.exit(255);
            }
        } else {
            log.debug("The interface {} is being torn down.", this);
        }

        online.set(false);
        OUT = false;
        IN = false;

        if (nonNull(parentInterface)) {
            parentInterface.getClients().decrementAndGet();
            parentInterface.spawnedInterfaces.remove(this);
        }

        if (Transport.getInstance().getInterfaces().contains(this)) {
            if (isFalse(initiator)) {
                Transport.getInstance().getInterfaces().remove(this);
            }
        }
    }

    private Optional<Channel> getChannel() {
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
}
