package io.reticulum;

import io.reticulum.constant.LinkConstant;
import io.reticulum.destination.DestinationType;
import io.reticulum.packet.HeaderType;
import io.reticulum.packet.PacketType;
import io.reticulum.transport.TransportType;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.msgpack.core.MessagePack;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.function.Function;

import static io.reticulum.utils.IdentityUtils.getRandomHash;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TTT {

    @Test
    void t() {
        var expected = "006415a41b";
        var i = 1679139867;
        System.out.println(Long.toBinaryString(i));
        var hash = getRandomHash();

        var array = intToByteArray(i);
        System.out.println(Arrays.toString(array));
        assertEquals(expected, Hex.encodeHexString(array));

//        assertEquals(expected, Hex.encodeHexString(BigInteger.valueOf(i).toByteArray()));
    }

    @Test
    void f() {
        System.out.println(LinkConstant.MDU);
    }

    public static byte[] intToByteArray(long value) {
        var result = new byte[5];
        var valArray = BigInteger.valueOf(value).toByteArray();
        for (int i = 0; i < valArray.length && i < result.length; i++) {
            result[i] = valArray[i];
        }
        if (valArray.length < result.length) {
            ArrayUtils.shift(result, result.length - valArray.length);
        }

        return result;
    }

    @ParameterizedTest
    @CsvSource(value = {
            "9223372036854775807;cf7fffffffffffffff",
            "-9223372036854775808;d38000000000000000",
            "0;00",
            "-2147483648;d280000000",
            "2147483647;ce7fffffff",
            "-32768;d18000",
            "32767;cd7fff",
            "-128;d080",
            "127;7f"
    }, delimiterString = ";")
    void ff(long l, String hex) throws IOException {
        var packer = MessagePack.newDefaultBufferPacker();
        packer.packLong(l);
        assertEquals(hex, Hex.encodeHexString(packer.toByteArray()));
    }

    @Test
    void v() {
        Function<Integer, String> toBin = i -> String.format("%8s", Integer.toBinaryString(i)).replace(' ', '0');

        var a = HeaderType.HEADER_2.getValue() << 6;
        System.out.println(toBin.apply(a));

        var b = TransportType.TUNNEL.getValue() << 4;
        System.out.println(toBin.apply(b));

        var c = DestinationType.LINK.getValue();
        System.out.println(toBin.apply((int) c));

        var d = PacketType.PROOF.getValue();
        System.out.println(toBin.apply((int) d));

        var result = a | b | c | d;
        System.out.println(toBin.apply(result));
    }
}
