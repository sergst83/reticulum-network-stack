package io.reticulum.link;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.msgpack.value.ImmutableArrayValue;
import org.msgpack.value.ValueFactory;

import java.time.Instant;

@Data
@AllArgsConstructor
public class UnpackedRequest {
    private Instant time;
    private byte[] requestPathHash;
    private byte[] data;

    public ImmutableArrayValue toValue() {
        return ValueFactory.newArray(
                ValueFactory.newFloat(time.toEpochMilli() / 1000d),
                ValueFactory.newBinary(requestPathHash),
                ValueFactory.newBinary(data)
        );
    }

    public static UnpackedRequest fromValue(ImmutableArrayValue value) {
        var time = Instant.ofEpochMilli(Double.valueOf(value.get(0).asFloatValue().toDouble() * 1000).longValue());
        var requestPathHash = value.get(1).asBinaryValue().asByteArray();
        var data = value.get(2).asBinaryValue().asByteArray();

        return new UnpackedRequest(time, requestPathHash, data);
    }
}
