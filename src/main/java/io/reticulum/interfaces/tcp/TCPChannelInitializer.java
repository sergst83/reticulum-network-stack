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
import io.reticulum.utils.InterfaceUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class TCPChannelInitializer extends ChannelInitializer<SocketChannel> implements HDLC, KISS {

    /**
     * Matches TCPInterface.HW_MTU in the reference implementation. This was
     * 1064, so any frame the reference sent above that size was rejected by
     * the deframer as too long.
     */
    public static final int HW_MTU = 262_144;

    /**
     * Deframer ceiling: the largest value {@code optimise_mtu()} can produce.
     * <p>
     * The per-interface limit is {@link ConnectionInterface#getHwMtu()}, derived
     * from the bitrate, and it is enforced in {@code Transport.inbound()} where
     * the reference enforces it. This decoder must therefore not be the narrower
     * of the two, or a configured high bitrate would raise the negotiated MTU
     * above what the pipeline will accept and frames would vanish in Netty.
     */
    public static final int MAX_FRAME_SIZE = 524_288;

    private final ConnectionInterface connectionInterface;
    private final boolean kissFraming;

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline()
                .addLast(
//                        new LoggingHandler(ByteBufFormat.HEX_DUMP),
                        new DelimiterBasedFrameDecoder(MAX_FRAME_SIZE, true, kissFraming ? delimitersKiss() : delimitersHdlc()),
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
            spownedInterface.inheritTrafficControl(serverInterface);
            spownedInterface.setKissFraming(kissFraming);
            spownedInterface.setIN(serverInterface.isIN());
            spownedInterface.setOUT(serverInterface.isOUT());
            spownedInterface.setBitrate(serverInterface.getBitrate());
            spownedInterface.optimiseMtu();
            spownedInterface.setAnnounceRateTarget(serverInterface.getAnnounceRateTarget());
            spownedInterface.setAnnounceRateGrace(serverInterface.getAnnounceRateGrace());
            spownedInterface.setAnnounceRatePenalty(serverInterface.getAnnounceRatePenalty());
            spownedInterface.setInterfaceMode(serverInterface.getInterfaceMode());
            spownedInterface.getOnline().set(true);

            //Ifac
            InterfaceUtils.initIFac(serverInterface);
            spownedInterface.setIfacNetName(serverInterface.getIfacNetName());
            spownedInterface.setIfacNetKey(serverInterface.getIfacNetKey());
            spownedInterface.setIfacKey(serverInterface.getIfacKey());
            spownedInterface.setIfacSize(serverInterface.getIfacSize());
            spownedInterface.setIdentity(serverInterface.getIdentity());
            spownedInterface.setIfacSignature(serverInterface.getIfacSignature());


            log.info("Spawned new TCPClient Interface: {}", spownedInterface.getInterfaceName());
            Transport.getInstance().getInterfaces().add(spownedInterface);
            serverInterface.getClients().incrementAndGet();
            serverInterface.spawnedInterfaces.add(spownedInterface);

            return spownedInterface;
        }
    }
}
