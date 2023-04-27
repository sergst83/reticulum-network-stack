package io.reticulum.packet;

import io.reticulum.destination.DestinationType;
import io.reticulum.transport.TransportType;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Function;

import static io.reticulum.utils.IdentityUtils.concatArrays;
import static java.nio.ByteOrder.BIG_ENDIAN;
import static org.junit.jupiter.api.Assertions.*;

class PacketTest {

    @Test
    void getFlags() throws IOException {
        Function<Integer, String> toBin = i -> String.format("%8s", Integer.toBinaryString(i)).replace(' ', '0');

        var a = HeaderType.HEADER_2.getValue() << 6;
        System.out.println(toBin.apply(a));

        var b = TransportType.TUNNEL.getValue() << 4;
        System.out.println(toBin.apply(b));

        var c = DestinationType.LINK.getValue() << 2;
        System.out.println(toBin.apply((int) c));

        var d = PacketType.PROOF.getValue();
        System.out.println(toBin.apply((int) d));

        var result = a | b | c | d;
        System.out.println(toBin.apply(result));

        var header = new byte[0];
        header = concatArrays(header, ByteBuffer.allocate(1).order(BIG_ENDIAN).put((byte) result).array());
        header = concatArrays(header, ByteBuffer.allocate(1).order(BIG_ENDIAN).put((byte) 2).array());

        System.out.println(Hex.encodeHexString(header));

        assertEquals(0b01111111, header[0]);
        assertEquals(2, header[1]);
    }
}