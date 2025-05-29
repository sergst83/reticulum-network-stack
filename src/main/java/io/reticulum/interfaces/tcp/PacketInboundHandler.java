package io.reticulum.interfaces.tcp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.reticulum.Transport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;

@Slf4j
@RequiredArgsConstructor
public class PacketInboundHandler extends SimpleChannelInboundHandler<byte[]> {

    private final TCPClientInterface connectionInterface;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] msg) throws Exception {
        if(ArrayUtils.isNotEmpty(msg)) {
            log.trace("channelRead0. context: {}, interface: {}, message: {}", ctx, connectionInterface, msg);
            connectionInterface.processIncoming(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("TCPClient Interface: {} is disconnected", connectionInterface.getInterfaceName());
        connectionInterface.detach();
        Transport.getInstance().getInterfaces().remove(connectionInterface);
        TCPServerInterface parentInterface = connectionInterface.getParentInterface();
        if (parentInterface != null) parentInterface.getClients().decrementAndGet();

        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error while handle inbound packet in interface {}", connectionInterface, cause);
        ctx.close();
    }

    // implement abstract method (netty >= 4.x, channelRead renamed to messageReceived in netty 5)
    public void messageReceived(ChannelHandlerContext ctx, Object msg) {
        //log.trace("message received. context: {}, interface: {}, message: {}", ctx.name(), connectionInterface.getInterfaceName() , msg);
        byte[] message = (byte[]) msg;
        if(ArrayUtils.isNotEmpty(message)) {
            connectionInterface.processIncoming(message);
        }
    }
}
