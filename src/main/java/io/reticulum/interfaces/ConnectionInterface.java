package io.reticulum.interfaces;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.reticulum.interfaces.auto.AutoInterface;

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

    void setName(String name);

    void processIncoming(final byte[] data);
    void processOutgoing(final byte[] data);

    public String getInterfaceName();
}
