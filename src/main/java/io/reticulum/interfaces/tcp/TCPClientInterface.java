package io.reticulum.interfaces.tcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
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
import java.time.Duration;

import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNullElse;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

@Slf4j
@Getter
@Setter
public class TCPClientInterface extends AbstractConnectionInterface implements HDLC, KISS {

    private static final int HW_MTU = 1064;
    private static final int BITRATE_GUESS = 10_000_000;
    private static final long INITIAL_CONNECT_TIMEOUT = 5_000; //milliseconds
    private static final long RECONNECT_WAIT = 5; //seconds

    private final Transport owner = Transport.getInstance();
    private ChannelFuture channelFuture;

    private Integer maxReconnectTries;

    private volatile boolean initiator = false;
    private volatile boolean reconnecting = false;
    private volatile boolean neverConnected = true;
    private volatile boolean writing = false;
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
        this.rxb.set(BigInteger.ZERO);
        this.txb.set(BigInteger.ZERO);

        this.IN = true;
        this.OUT = false;

        this.interfaceMode = InterfaceMode.MODE_FULL;
        this.bitrate = BITRATE_GUESS;
    }

    public TCPClientInterface(
            String name,
            String targetHost,
            Integer targetPort,
            Integer maxReconnectTries,
            Boolean kissFraming,
            Boolean i2pTunneled,
            Long connectionTimeoutMs
    ) {
        this();
        this.interfaceName = name;
        this.maxReconnectTries = requireNonNullElse(maxReconnectTries, 0);

        if (nonNull(connectionTimeoutMs)) {
            this.connectionTimeout = connectionTimeoutMs;
        }
        if (nonNull(targetHost)) {
            this.targetHost = targetHost;
        }
        if (nonNull(targetPort)) {
            this.targetPort = targetPort;
        }
        if (nonNull(kissFraming)) {
            this.kissFraming = kissFraming;
        }
        if (nonNull(i2pTunneled)) {
            this.i2pTunneled = i2pTunneled;
        }
    }

    public void run() {
        try {
            connect(null);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized void processIncoming(byte[] data) {
        var processingData = kissFraming ? unmaskKiss(data) : unmaskHdlc(data);
        this.rxb.accumulateAndGet(BigInteger.valueOf(processingData.length), BigInteger::add);
        if (nonNull(parentInterface)) {
            ((AbstractConnectionInterface) parentInterface).getRxb()
                    .accumulateAndGet(BigInteger.valueOf(processingData.length), BigInteger::add);
        }

        owner.inbound(processingData, this);
    }

    @Override
    public synchronized void processOutgoing(byte[] data) {
        if (online.get()) {
            try(var os = new ByteArrayOutputStream()) {
                writing = true;

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

                channelFuture.sync().channel().writeAndFlush(os.toByteArray());

                writing = false;
                txb.accumulateAndGet(BigInteger.valueOf(data.length), BigInteger::add);
                if (nonNull(parentInterface)) {
                    ((AbstractConnectionInterface) parentInterface).getTxb()
                            .accumulateAndGet(BigInteger.valueOf(data.length), BigInteger::add);
                }
            } catch (Exception e) {
                log.error("Exception occurred while transmitting via {}, tearing down interfac.", this, e);
            }
        }
    }

    private synchronized void reconnect() {
        if (initiator) {
            if (isFalse(reconnecting)) {
                reconnecting = true;
                var attempts = 0;
                while (isFalse(online.get())) {
                    try {
                        Thread.sleep(Duration.ofSeconds(RECONNECT_WAIT).toMillis());
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    attempts++;

                    if (attempts > maxReconnectTries) {
                        log.error("Max reconnection attempts reached for {}", this);
                        teardown();
                        break;
                    }

                    try {
                        connect(null);
                    } catch (Exception e) {
                        log.debug("Connection attempt for {}  failed.", this, e);
                    }
                }

                if (isFalse(neverConnected)) {
                    log.info("Reconnected socket for {}", this);
                }

                reconnecting = false;

                if (isFalse(kissFraming)) {
                    owner.synthesizeTunnel(this);
                }
            }
        } else {
            log.error("Attempt to reconnect on a non-initiator TCP interface. This should not happen");
            throw new IllegalStateException("Attempt to reconnect on a non-initiator TCP interface");
        }
    }

    private synchronized boolean connect(Boolean initial) throws InterruptedException {
        var init = BooleanUtils.isTrue(initial);

        var delimiter = kissFraming ? delimitersKiss() : delimitersHdlc();
        var packetInboundHandler = new PacketInboundHandler(this);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            if (init) {
                log.debug("Establishing TCP connection for {} ...", this);
            }
            Bootstrap bootstrap = new Bootstrap();
            bootstrap
                    .group(workerGroup).channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline()
                                    .addLast(
                                            new DelimiterBasedFrameDecoder(HW_MTU, true, delimiter),
                                            new ByteArrayDecoder(),
                                            new ByteArrayEncoder(),
                                            packetInboundHandler
                                    );
                        }
                    });

            // Start the client.
            ChannelFuture channelFuture = bootstrap.connect(targetHost, targetPort).sync();
            this.channelFuture = channelFuture;

            // Wait until the connection is closed.
            channelFuture.channel().closeFuture().sync();

            log.debug("TCP connection for {} established", this);
        } catch (Exception e) {
            if (init) {
                log.error("Initial connection for {}  could not be established.", this, e);
                log.error("Leaving unconnected and retrying connection in {}  seconds.", RECONNECT_WAIT);
                return false;
            } else {
                throw e;
            }
        } finally {
            workerGroup.shutdownGracefully();
            online.set(false);
        }

        online.set(true);
        writing = false;
        neverConnected = false;

        return true;
    }

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

    @Override
    public String toString() {
        return getInterfaceName() + "/" + targetHost + ":" + targetPort;
    }

    private void teardown() {
        if (initiator && isFalse(detached)) {
            log.error("The interface {} experienced an unrecoverable error and is being torn down. Restart Reticulum to attempt to open this interface again.", this);
            if (owner.getOwner().isPanicOnIntefaceError()) {
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

        if (owner.getInterfaces().contains(this)) {
            if (isFalse(initiator)) {
                owner.getInterfaces().remove(this);
            }
        }
    }
}
