package io.reticulum.interfaces.backbone;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.reticulum.Transport;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.interfaces.HDLC;
import io.reticulum.utils.InterfaceUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Netty channel initializer for Backbone TCP connections.
 *
 * <p>Used for both client-initiated connections and server-spawned connections.
 * HDLC framing with 1 MiB frame limit is configured, and a
 * {@link BackbonePacketHandler} is added to dispatch incoming frames.
 */
@Slf4j
@RequiredArgsConstructor
public class BackboneChannelInitializer extends ChannelInitializer<SocketChannel> implements HDLC {

    private final ConnectionInterface connectionInterface;

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline().addLast(
                new DelimiterBasedFrameDecoder(BackboneServerInterface.HW_MTU, true, delimitersHdlc()),
                new ByteArrayDecoder(),
                new ByteArrayEncoder(),
                new BackbonePacketHandler(resolveClientInterface(ch))
        );
    }

    /**
     * Returns the {@link BackboneClientInterface} that should receive frames from
     * this channel.
     *
     * <ul>
     *   <li>If the owning interface is already a client (initiator mode), return it directly.</li>
     *   <li>If the owning interface is a server, spawn a new client interface for the
     *       accepted connection and register it with Transport.</li>
     * </ul>
     */
    private BackboneClientInterface resolveClientInterface(Channel channel) {
        if (connectionInterface instanceof BackboneClientInterface) {
            return (BackboneClientInterface) connectionInterface;
        }

        // Server side — spawn a new interface for this accepted connection
        var serverInterface = (BackboneServerInterface) connectionInterface;

        var spawned = new BackboneClientInterface(
                "Client on " + serverInterface.getInterfaceName(),
                channel,
                false
        );

        spawned.setParentInterface(serverInterface);
        spawned.setIN(serverInterface.isIN());
        spawned.setOUT(serverInterface.isOUT());
        spawned.setBitrate(serverInterface.getBitrate());
        spawned.setAnnounceRateTarget(serverInterface.getAnnounceRateTarget());
        spawned.setAnnounceRateGrace(serverInterface.getAnnounceRateGrace());
        spawned.setAnnounceRatePenalty(serverInterface.getAnnounceRatePenalty());
        spawned.setInterfaceMode(serverInterface.getInterfaceMode());
        spawned.getOnline().set(true);

        // Copy IFAC credentials from the server interface
        InterfaceUtils.initIFac(serverInterface);
        spawned.setIfacNetName(serverInterface.getIfacNetName());
        spawned.setIfacNetKey(serverInterface.getIfacNetKey());
        spawned.setIfacKey(serverInterface.getIfacKey());
        spawned.setIfacSize(serverInterface.getIfacSize());
        spawned.setIdentity(serverInterface.getIdentity());
        spawned.setIfacSignature(serverInterface.getIfacSignature());

        log.info("Spawned new BackboneClient Interface: {}", spawned);
        Transport.getInstance().getInterfaces().add(spawned);
        serverInterface.getClients().incrementAndGet();
        serverInterface.spawnedInterfaces.add(spawned);

        return spawned;
    }
}