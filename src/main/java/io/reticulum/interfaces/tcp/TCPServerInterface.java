package io.reticulum.interfaces.tcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.reticulum.Transport;
import io.reticulum.interfaces.AbstractConnectionInterface;
import io.reticulum.interfaces.HDLC;
import io.reticulum.interfaces.InterfaceMode;
import io.reticulum.interfaces.KISS;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@Getter
@Setter
public class TCPServerInterface extends AbstractConnectionInterface implements HDLC, KISS {

    private static final int BITRATE_GUESS = 10_000_000;

    private ChannelFuture channelFuture;

    private volatile boolean detached = false;

    @JsonProperty("i2p_tunneled")
    private boolean i2pTunneled = false;
    @JsonProperty("listen_port")
    private int listenPort;

    protected List<TCPClientInterface> spawnedInterfaces = new CopyOnWriteArrayList<>();

    public TCPServerInterface() {
        super();
        this.rxb.set(BigInteger.ZERO);
        this.txb.set(BigInteger.ZERO);

        this.IN = true;
        this.OUT = false;

        this.interfaceMode = InterfaceMode.MODE_FULL;
        this.bitrate = BITRATE_GUESS;

        if (isNull(ifacSize)) {
            ifacSize = 16;
        }
    }

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

    public void startListening() throws InterruptedException {
        var self = this;
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap
                .group(bossGroup, workerGroup)
                // Use NioserVersocketChannel as a channel for the channel to implement
                .channel(NioServerSocketChannel.class)
                // Set the thread queue to wait for the number of connections
                .option(ChannelOption.SO_BACKLOG, 1024)
                // Set up to keep the activity connection status
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new TCPChannelInitializer(self, false));

        // Start server .
        this.channelFuture = bootstrap.bind(listenPort)
                .addListener(
                        (ChannelFutureListener) future -> future.channel().closeFuture()
                                .addListener((ChannelFutureListener) closeFeature -> {
                                    // Wait until the connection is closed.
                                    bossGroup.shutdownGracefully();
                                    workerGroup.shutdownGracefully();
                                    online.set(false);
                                })
                ).sync();
    }

    public int clients() {
        return spawnedInterfaces.size();
    }

    @Override
    public synchronized void processIncoming(byte[] data) {
        var processingData = unmaskHdlc(data);
        this.rxb.accumulateAndGet(BigInteger.valueOf(processingData.length), BigInteger::add);

        Transport.getInstance().inbound(processingData, this);
    }

    /**
     * server processees only incoming packets <br/>
     * outgoing packets processed by attached client interfaces see {@link io.reticulum.interfaces.tcp.TCPChannelInitializer}
     *
     * @param data ignored
     */
    @Override
    public synchronized void processOutgoing(byte[] data) {
        //pass
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

    @Override
    public String toString() {
        return getInterfaceName() + "/" +
                Optional.ofNullable(channelFuture)
                        .map(ChannelFuture::channel)
                        .map(Channel::localAddress)
                        .map(socketAddress -> ((InetSocketAddress) socketAddress).getHostString())
                        .orElse("")
                + ":" + listenPort;
    }
}
