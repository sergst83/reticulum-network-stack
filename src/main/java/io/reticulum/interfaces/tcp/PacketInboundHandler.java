package io.reticulum.interfaces.tcp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.reticulum.interfaces.ConnectionInterface;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class PacketInboundHandler extends SimpleChannelInboundHandler<byte[]> {

    private final ConnectionInterface connectionInterface;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] msg) throws Exception {
        //log.trace("channelRead0. context: {}, interface: {}, message: {}", ctx.name(), connectionInterface.getInterfaceName() , msg);
        connectionInterface.processIncoming(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error while handle inbound packet in interface {}", connectionInterface, cause);
        ctx.close();
    }

    // implement abstract method (netty >= 4.x, channelRead renamed to messageReceived in netty 5)
    public void messageReceived(ChannelHandlerContext ctx, Object msg) {
        //log.trace("message received. context: {}, interface: {}, message: {}", ctx.name(), connectionInterface.getInterfaceName() , msg);
        connectionInterface.processIncoming((byte[]) msg);
    }
}
