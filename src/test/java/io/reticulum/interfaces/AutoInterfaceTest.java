package io.reticulum.interfaces;

import io.reticulum.interfaces.autointerface.AutoInterface;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
}