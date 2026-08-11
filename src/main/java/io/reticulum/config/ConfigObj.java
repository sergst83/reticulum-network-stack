package io.reticulum.config;

import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
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
            .annotationIntrospector(new IgnoreThreadMembersIntrospector())
            .build();

    /**
     * Hides everything {@link Thread} declares from Jackson.
     * <p>
     * The interfaces deserialized here extend {@code Thread} (via
     * {@code AbstractConnectionInterface}), so without this Jackson also introspects Thread's own
     * members. That is never wanted — a config key such as {@code name} or {@code daemon} would
     * mutate the thread rather than the interface — and on a modern JDK it is fatal: Thread gained
     * a private {@code volatile boolean interrupted} field (JDK 14+) that Jackson pairs with the
     * public {@code isInterrupted()} getter as a mutator, and a private native
     * {@code setCurrentThread(Thread)} (JDK 21) that it detects as a setter. Both are then passed
     * to {@code setAccessible()}, which throws {@code InaccessibleObjectException} because
     * {@code java.base} does not open {@code java.lang}. Filtering by declaring class keeps this
     * working across JDK versions instead of enumerating members that vary between them.
     */
    private static class IgnoreThreadMembersIntrospector extends JacksonAnnotationIntrospector {
        @Override
        public boolean hasIgnoreMarker(AnnotatedMember m) {
            return m.getDeclaringClass() == Thread.class || super.hasIgnoreMarker(m);
        }
    }

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
