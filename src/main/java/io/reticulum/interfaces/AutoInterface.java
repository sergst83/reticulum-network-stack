package io.reticulum.interfaces;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;

@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@ToString(callSuper = true)
@Slf4j
@Setter
@Getter
public class AutoInterface extends AbstractConnectionInterface {

    @RequiredArgsConstructor
    @Getter
    enum DiscoveryScope {
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

    @JsonProperty("group_id")
    private String groupId = "reticulum";

    @JsonProperty("discovery_scope")
    private DiscoveryScope discoveryScope;

    @Override
    public void setEnabled(boolean enabled) {
        if (IS_OS_WINDOWS) {
            log.error(
                    "AutoInterface is not currently supported on Windows, disabling interface.\n"
                    + "Please remove this AutoInterface instance from your configuration file.\n"
                    + "You will have to manually configure other interfaces for connectivity."
            );
            super.setEnabled(false);
        } else {
            super.setEnabled(enabled);
        }
    }
}
