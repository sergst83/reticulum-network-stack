package io.reticulum;

import io.reticulum.constant.LinkConstant;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.msgpack.core.MessagePack;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

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
}
