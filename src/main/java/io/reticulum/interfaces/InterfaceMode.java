package io.reticulum.interfaces;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;

@Getter
@RequiredArgsConstructor
public enum InterfaceMode {

    MODE_FULL(List.of("full"), Byte.decode("0x01")),
    MODE_POINT_TO_POINT(List.of("pointtopoint", "ptp"), Byte.decode("0x02")),
    MODE_ACCESS_POINT(List.of("access_point", "accesspoint", "ap"), Byte.decode("0x03")),
    MODE_ROAMING(List.of("roaming"), Byte.decode("0x04")),
    MODE_BOUNDARY(List.of("boundary"), Byte.decode("0x05")),
    MODE_GATEWAY(List.of("gateway", "gw"), Byte.decode("0x06")),
    ;

    private final List<String> aliases;
    private final byte modeValue;

    @JsonCreator
    public static InterfaceMode parseName(@NonNull String modeName) {
        return Arrays.stream(InterfaceMode.values())
                .filter(interfaceMode -> interfaceMode.getAliases().contains(modeName.strip().toLowerCase()))
                .findFirst()
                .orElseThrow();
    }
}
