package io.reticulum.vendor.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.reticulum.interfaces.ConnectionInterface;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;

@ToString
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ConfigObj {

    private static final YAMLMapper mapper = YAMLMapper.builder()
            .configure(FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    public static ConfigObj initConfig(Path configPath) throws IOException {
        return mapper.readValue(configPath.toFile(), ConfigObj.class);
    }

    private Reticulum reticulum;
    private Map<String, ConnectionInterface> interfaces;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reticulum {

        @JsonProperty("share_instance")
        private Boolean shareInstance;

        @JsonProperty("shared_instance_port")
        private Integer sharedInstancePort;

        @JsonProperty("instance_control_port")
        private Integer instanceControlPort;

        @JsonProperty("enable_transport")
        private Boolean enableTransport;

        @JsonProperty("panic_on_interface_error")
        private Boolean panicOnInterfaceError;

        @JsonProperty("use_implicit_proof")
        private Boolean useImplicitProof;
    }
}
