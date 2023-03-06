package io.reticulum.interfaces.auto;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@RequiredArgsConstructor
@Getter
public enum DiscoveryScope {
    SCOPE_LINK("link", "2"),
    SCOPE_ADMIN("admin", "4"),
    SCOPE_SITE("site", "5"),
    SCOPE_ORGANISATION("organisation", "8"),
    SCOPE_GLOBAL("global", "e"),
    ;

    private final String scopeName;
    private final String scopeValue;

    @JsonCreator
    public static DiscoveryScope parseByScopeName(String scopeName) {
        return Arrays.stream(DiscoveryScope.values())
                .filter(discoveryScope -> discoveryScope.getScopeName().equals(scopeName))
                .findFirst()
                .orElseThrow();
    }
}
