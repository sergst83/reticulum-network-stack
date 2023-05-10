package io.reticulum.interfaces;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.reticulum.interfaces.auto.AutoInterface;
import io.reticulum.utils.IdentityUtils;

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type"
)
@JsonSubTypes({
        @Type(value = AutoInterface.class, name = "AutoInterface")
})
public interface ConnectionInterface {

    default String getType() {
        return getClass().getSimpleName();
    }

    boolean isEnabled();

    void processIncoming(final byte[] data);
    void processOutgoing(final byte[] data);

    void setInterfaceName(String name);
    String getInterfaceName();

    default List<?> getAnnounceQueue() {
        return List.of();
    }

    default void detach() {
        //pass
    }

    default byte[] getHash() {
        return IdentityUtils.fullHash(getInterfaceName().getBytes(UTF_8));
    };
}
