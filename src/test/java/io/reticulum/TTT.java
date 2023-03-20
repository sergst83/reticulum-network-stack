package io.reticulum;

import io.reticulum.constant.LinkConstant;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.Test;

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
}
