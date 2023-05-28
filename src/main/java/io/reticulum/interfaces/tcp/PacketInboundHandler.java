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
        connectionInterface.processIncoming(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error while handle inbound packet in interface {}", connectionInterface, cause);
        ctx.close();
    }
}
