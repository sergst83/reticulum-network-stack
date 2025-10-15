package io.reticulum.packet.data;

import com.igormaznitsa.jbbp.utils.JBBPUtils;
import io.reticulum.destination.DestinationType;
import io.reticulum.packet.ContextType;
import io.reticulum.packet.HeaderType;
import io.reticulum.packet.PacketContextType;
import io.reticulum.packet.PacketType;
import io.reticulum.transport.TransportType;
import io.reticulum.utils.IdentityUtils;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;

import static io.reticulum.packet.PacketType.ANNOUNCE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataPacketConverterTest {

    @Test
    void fromBytes() {
    }

    @Test
    void toBytes() {
        var flags = new Flags();
        flags.setAccessCodes(false);
        flags.setHeaderType(HeaderType.HEADER_2);
        flags.setPacketType(PacketType.PROOF);
        flags.setPropagationType(TransportType.TUNNEL);
        flags.setDestinationType(DestinationType.LINK);
        flags.setContextType(ContextType.FLAG_SET);
        var header = new Header( new DataPacket());
        header.setHops((byte) 2);
        header.setFlags(flags);

        var data = new DataPacket();
        data.setContext(PacketContextType.KEEPALIVE);
        data.setHeader(header);
        data.setData("data".getBytes(UTF_8));
        data.setAddresses(new Addresses(data));
        data.getAddresses().setHash1(IdentityUtils.fullHash("addresss1".getBytes(UTF_8)));
        data.getAddresses().setHash2(IdentityUtils.fullHash("addresss2".getBytes(UTF_8)));

        var bitString = JBBPUtils.bin2str(DataPacketConverter.toBytes(data), true);

        assertTrue(bitString.startsWith("01111111 00000010"));
    }

    @Test
    void announceFromBytes() throws DecoderException {
        var dataHex = "01006f8899febf9e183a6cf35983dda522800098b1af48b110c33d6b32d859b1fa866d8a2c0885b9d061bcc99bf2d2c0b7d160825ac09c60ed64ce3a68383d51b84a8eab567215aeb464bde1f329f58579c5942efa4a32514efa3bac18dc4fabe8f80065c785077cf554c82529e6b46410ee19017b4158108594990f2e7bc7d8168daccbb6c42ff749fb0a65ec7a7186fe327656d6e174fb13122bdb53ac1abec843cb20cc7a0f";
        var data = Hex.decodeHex(dataHex);

        var dataPacket = DataPacketConverter.fromBytes(data);

        assertEquals(ANNOUNCE, dataPacket.getHeader().getFlags().getPacketType());
        assertNull(dataPacket.getIfac());
    }
}