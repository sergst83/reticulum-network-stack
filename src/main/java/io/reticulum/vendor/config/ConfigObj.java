package io.reticulum.vendor.config;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.reticulum.interfaces.ConnectionInterface;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.apache.commons.collections4.MapUtils;

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

    private ReticulumConf reticulum;
    private Map<String, ConnectionInterface> interfaces;

    public void setInterfaces(Map<String, ConnectionInterface> interfaces) {
        this.interfaces = interfaces;
        if (MapUtils.isNotEmpty(this.interfaces)) {
            this.interfaces.forEach((name, connectionInterface) -> connectionInterface.setInterfaceName(name));
        }
    }
}
