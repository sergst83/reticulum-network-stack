package io.reticulum.interfaces.backbone;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.reticulum.Transport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;

/**
 * Netty inbound handler for Backbone TCP frames.
 *
 * <p>Forwards decoded HDLC frames to
 * {@link BackboneClientInterface#processIncoming(byte[])} and handles channel
 * lifecycle events (disconnect, errors).
 */
@Slf4j
@RequiredArgsConstructor
public class BackbonePacketHandler extends SimpleChannelInboundHandler<byte[]> {

    private final BackboneClientInterface connectionInterface;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] msg) {
        if (ArrayUtils.isNotEmpty(msg)) {
            log.trace("channelRead0. context: {}, interface: {}", ctx, connectionInterface);
            connectionInterface.processIncoming(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("BackboneClient Interface {} disconnected.", connectionInterface.getInterfaceName());
        connectionInterface.detach();

        // Only remove spawned (non-initiator) interfaces from Transport.
        // Initiator interfaces stay registered so they can continue to route
        // traffic after reconnection.
        if (!connectionInterface.isInitiator()) {
            Transport.getInstance().getInterfaces().remove(connectionInterface);
        }

        var parent = connectionInterface.getParentInterface();
        if (parent instanceof BackboneServerInterface) {
            BackboneServerInterface serverInterface = (BackboneServerInterface) parent;
            serverInterface.getClients().decrementAndGet();
            serverInterface.spawnedInterfaces.remove(connectionInterface);
        }

        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error while handling inbound packet in interface {}", connectionInterface, cause);
        ctx.close();
    }

    // Compatibility shim for Netty 5 API (channelRead0 → messageReceived)
    public void messageReceived(ChannelHandlerContext ctx, Object msg) {
        byte[] message = (byte[]) msg;
        if (ArrayUtils.isNotEmpty(message)) {
            connectionInterface.processIncoming(message);
        }
    }
}