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
import io.reticulum.interfaces.ConnectionInterface;
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
    private static final long RECONNECT_WAIT = 5; //seconds

    private ChannelFuture channelFuture;
    private Channel channel;

    private Integer maxReconnectTries;

    private boolean initiator;
    private volatile boolean reconnecting = false;
    private volatile boolean neverConnected = true;
    private volatile boolean detached = false;

    private ConnectionInterface parentInterface;
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
            connect(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void launch() {
        start();
    }

    @Override
    public synchronized void processIncoming(byte[] data) {
        var processingData = kissFraming ? unmaskKiss(data) : unmaskHdlc(data);
        this.rxb.accumulateAndGet(BigInteger.valueOf(processingData.length), BigInteger::add);
        if (nonNull(parentInterface)) {
            ((AbstractConnectionInterface) parentInterface).getRxb()
                    .accumulateAndGet(BigInteger.valueOf(processingData.length), BigInteger::add);
        }

        Transport.getInstance().inbound(processingData, this);
    }

    @Override
    public synchronized void processOutgoing(byte[] data) {
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
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (initiator) {
                    if (online.get()) {
                        cancel();
                    } else {
                        var attempts = 0;
                        if (isFalse(reconnecting)) {
                            reconnecting = true;
                            attempts++;
                            if (attempts > maxReconnectTries) {
                                log.error("Max reconnection attempts reached for {}", this);
                                teardown();
                                cancel();
                            } else {
                                reconnect(attempts);
                            }
                        }
                    }
                } else {
                    log.error("Attempt to reconnect on a non-initiator TCP interface. This should not happen");
                    throw new IllegalStateException("Attempt to reconnect on a non-initiator TCP interface");
                }
            }
        }, 500, Duration.ofSeconds(RECONNECT_WAIT).toMillis());
    }

    private synchronized void reconnect(final int currentAttempt) {
        try {
            connect(null);
            reconnecting = false;
            if (isFalse(neverConnected)) {
                log.info("Reconnected socket for {}", this);
            }
            if (isFalse(kissFraming)) {
                Transport.getInstance().synthesizeTunnel(this);
            }
        } catch (Exception e) {
            log.debug("Connection attempt for {}  failed.", currentAttempt, e);
        }
    }

    private synchronized boolean connect(Boolean initial) throws InterruptedException {
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
                                if (isFalse(detached)) {
                                    startReconnecting();
                                } else {
                                    // Wait until the connection is closed.
                                    workerGroup.shutdownGracefully();
                                    online.set(false);
                                }
                            })
                    ).sync();

            log.debug("TCP connection for {} established", this);
        } catch (Exception e) {
            if (init) {
                log.error("Initial connection for {}  could not be established.", this, e);
                log.error("Leaving unconnected and retrying connection in {}  seconds.", RECONNECT_WAIT);
                return false;
            } else {
                throw e;
            }
        }

        online.set(true);
        neverConnected = false;

        return true;
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

            channelFuture = null;
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
            ((AbstractConnectionInterface) parentInterface).getClients().decrementAndGet();
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
