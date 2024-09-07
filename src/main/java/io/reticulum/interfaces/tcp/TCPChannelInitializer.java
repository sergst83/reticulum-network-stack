package io.reticulum.interfaces.tcp;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.reticulum.interfaces.HDLC;
import io.reticulum.interfaces.KISS;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class TCPChannelInitializer extends ChannelInitializer<SocketChannel> implements HDLC, KISS {

    private static final int HW_MTU = 1064;

    private final PacketInboundHandler packetInboundHandler;
    private final boolean kissFraming;

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline()
                .addLast(
//                        new LoggingHandler(ByteBufFormat.HEX_DUMP),
                        new DelimiterBasedFrameDecoder(HW_MTU, true, kissFraming ? delimitersKiss() : delimitersHdlc()),
                        new ByteArrayDecoder(),
                        new ByteArrayEncoder(),
                        packetInboundHandler
                );
    }
}
