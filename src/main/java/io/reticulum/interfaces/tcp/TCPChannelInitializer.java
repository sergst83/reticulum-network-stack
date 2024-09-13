package io.reticulum.interfaces.tcp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.reticulum.Transport;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.interfaces.HDLC;
import io.reticulum.interfaces.KISS;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static org.apache.commons.lang3.ArrayUtils.isNotEmpty;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

@Slf4j
@RequiredArgsConstructor
public class TCPChannelInitializer extends ChannelInitializer<SocketChannel> implements HDLC, KISS {

    private static final int HW_MTU = 1064;

    private final ConnectionInterface connectionInterface;
    private final boolean kissFraming;

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline()
                .addLast(
//                        new LoggingHandler(ByteBufFormat.HEX_DUMP),
                        new DelimiterBasedFrameDecoder(HW_MTU, true, kissFraming ? delimitersKiss() : delimitersHdlc()),
                        new ByteArrayDecoder(),
                        new ByteArrayEncoder(),
                        new PacketInboundHandler(createInterface(ch))
                );
    }

    private TCPClientInterface createInterface(Channel channel) {
        if (connectionInterface instanceof TCPClientInterface) {
            return (TCPClientInterface) connectionInterface;
        } else {
            var serverInterface = (TCPServerInterface) connectionInterface;
            var spownedInterface = new TCPClientInterface(
                    "Client on " + serverInterface.getInterfaceName(),
                    channel,
                    serverInterface.isI2pTunneled()
            );
            spownedInterface.setParentInterface(serverInterface);
            spownedInterface.setKissFraming(kissFraming);
            spownedInterface.setIN(serverInterface.isIN());
            spownedInterface.setOUT(serverInterface.isOUT());
            spownedInterface.setBitrate(serverInterface.getBitrate());
            spownedInterface.setIfacSize(serverInterface.getIfacSize());
            spownedInterface.setIfacNetName(serverInterface.getIfacNetName());
            spownedInterface.setIfacKey(serverInterface.getIfacKey());
            if (isNotBlank(spownedInterface.getIfacNetName()) || isNotEmpty(spownedInterface.getIfacKey())) {

            }
            spownedInterface.setAnnounceRateTarget(serverInterface.getAnnounceRateTarget());
            spownedInterface.setAnnounceRateGrace(serverInterface.getAnnounceRateGrace());
            spownedInterface.setAnnounceRatePenalty(serverInterface.getAnnounceRatePenalty());
            spownedInterface.setInterfaceMode(serverInterface.getInterfaceMode());
            spownedInterface.getOnline().set(true);

            log.info("Spawned new TCPClient Interface: {}", serverInterface.getInterfaceName());
            Transport.getInstance().getInterfaces().add(spownedInterface);
            serverInterface.getClients().incrementAndGet();

            return spownedInterface;
        }
    }
}
