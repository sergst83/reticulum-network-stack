package io.reticulum.utils;

import lombok.NoArgsConstructor;
import org.apache.commons.lang3.ArrayUtils;

import java.math.BigInteger;

import static lombok.AccessLevel.PRIVATE;

@NoArgsConstructor(access = PRIVATE)
public final class CommonUtils {

    public static void panic() {
        System.exit(255);
    }

    public static void exit() {
        System.exit(0);
    }

    public static byte[] longToByteArray(long value, int arraySize) {
        var result = new byte[arraySize];
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
