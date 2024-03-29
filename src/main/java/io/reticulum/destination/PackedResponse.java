package io.reticulum.destination;

import lombok.Value;
import org.msgpack.value.ImmutableArrayValue;
import org.msgpack.value.ValueFactory;

@Value
public class PackedResponse {
    byte[] response;
    byte[] requestId;

    public ImmutableArrayValue toValue() {
        return ValueFactory.newArray(
                ValueFactory.newBinary(requestId),
                ValueFactory.newBinary(response)
        );
    }
}
