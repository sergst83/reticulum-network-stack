package io.reticulum.interfaces;

import io.reticulum.interfaces.auto.AutoInterface;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;

import static java.util.Objects.nonNull;

class AutoInterfaceTest {

    @ParameterizedTest
    @CsvSource(value = {
            "reticulum ff12:0:d70b:fb1c:16e4:5e39:485e:31e1",
            "qwerty ff12:0:4be3:3532:fb78:4c48:1296:75f9",
            "a ff12:0:8112:ca1b:bdca:fac2:31b3:9a23",
            "az ff12:0:da37:bf74:aeef:ae94:9fdf:c90d",
            "Тест ff12:0:c06a:5b36:32e8:a99f:9cad:1639"
    }, delimiterString = " ")
    void getMcastDiscoveryAddress(String groupId, String dicoveryAddress) {
        var autoInterface = new AutoInterface();
        autoInterface.setGroupId(groupId);

        Assertions.assertEquals(dicoveryAddress, autoInterface.getMcastDiscoveryAddress());
    }

    @Test
    void v() {
        System.out.println(Byte.decode("0x7E"));
        System.out.println(new String(new byte[] {Byte.decode("0x7E")}));

        System.out.println(Byte.decode("0x7D"));
        System.out.println(new String(new byte[] {Byte.decode("0x7D")}));

        System.out.println(Byte.decode("0x7D") ^ Byte.decode("0x20"));
        System.out.println(new String(new byte[] {(byte) (Byte.decode("0x7D") ^ Byte.decode("0x20"))}));
    }

    @Test
    void f() {
        var b = "test_string";
    }


    private static byte[] escape(byte[] data) {
        byte FLAG = Byte.decode("0x7E");
        byte ESC = Byte.decode("0x7D");
        byte ESC_MASK = Byte.decode("0x20");
        var result = new byte[0];
        if (nonNull(data) && data.length > 0) {
            var buffer = ByteBuffer.wrap(data);
            for (int i = 0; i < data.length; i++) {
                if (data[i] == ESC) {
                    buffer.position(i);
                    buffer.put(new byte[]{ESC, (byte) (ESC ^ ESC_MASK)});
                }
                if (data[i] == FLAG) {
                    buffer.position(i);
                    buffer.put(new byte[]{ESC, (byte) (FLAG ^ ESC_MASK)});
                }
            }
            result = buffer.array();
        }

        return result;
    }
}