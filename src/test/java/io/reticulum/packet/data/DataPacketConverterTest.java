package io.reticulum.packet.data;

import com.igormaznitsa.jbbp.utils.JBBPUtils;
import io.reticulum.destination.DestinationType;
import io.reticulum.packet.HeaderType;
import io.reticulum.packet.PacketType;
import io.reticulum.transport.TransportType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

class DataPacketConverterTest {

    @Test
    void fromBytes() {
    }

    @Test
    void toBytes() throws IOException {
        var flags = new Flags();
        flags.setAccessCodes(false);
        flags.setHeaderType(HeaderType.HEADER_2);
        flags.setPacketType(PacketType.PROOF);
        flags.setPropagationType(TransportType.TUNNEL);
        flags.setDestinationType(DestinationType.LINK);
        var header = new Header( 2, flags);

        var data = new DataPacket();
        data.setHeader(header);

        var bitString = JBBPUtils.bin2str(DataPacketConverter.toBytes(data), true);

        Assertions.assertEquals("01111111 00000010", bitString);
    }
}