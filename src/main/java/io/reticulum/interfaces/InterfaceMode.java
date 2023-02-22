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

    MODE_FULL(List.of("full"), 1),
    MODE_POINT_TO_POINT(List.of("pointtopoint", "ptp"), 2),
    MODE_ACCESS_POINT(List.of("access_point", "accesspoint", "ap"), 3),
    MODE_ROAMING(List.of("roaming"), 4),
    MODE_BOUNDARY(List.of("boundary"), 5),
    MODE_GATEWAY(List.of("gateway", "gw"), 6),
    ;

    private final List<String> aliases;
    private final int modeValue;

    @JsonCreator
    public static InterfaceMode parseName(@NonNull String modeName) {
        return Arrays.stream(InterfaceMode.values())
                .filter(interfaceMode -> interfaceMode.getAliases().contains(modeName.strip().toLowerCase()))
                .findFirst()
                .orElseThrow();
    }
}
