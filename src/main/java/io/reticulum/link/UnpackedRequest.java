package io.reticulum.link;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.msgpack.value.ImmutableArrayValue;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.time.Instant;

import static java.util.Objects.isNull;

@Data
@AllArgsConstructor
public class UnpackedRequest {
    private Instant time;
    private byte[] requestPathHash;
    /**
     * The request data as sent. The reference packs whatever the caller passes
     * as {@code data} ({@code RNS/Link.py:487}) — bytes, nil, or a structure:
     * rngit sends a map on every request. Kept as a msgpack value so nothing is
     * lost or coerced on the way to the request handler; a missing element and
     * nil both become a nil value.
     *
     * @return the request data; a nil value when none was sent
     */
    private Value data;

    public ImmutableArrayValue toValue() {
        return ValueFactory.newArray(
                ValueFactory.newFloat(time.toEpochMilli() / 1000d),
                ValueFactory.newBinary(requestPathHash),
                isNull(data) ? ValueFactory.newNil() : data
        );
    }

    public static UnpackedRequest fromValue(ImmutableArrayValue value) {
        var time = Instant.ofEpochMilli(Double.valueOf(value.get(0).asFloatValue().toDouble() * 1000).longValue());
        var requestPathHash = value.get(1).asBinaryValue().asByteArray();
        var data = value.size() > 2 ? value.get(2) : ValueFactory.newNil();
        return new UnpackedRequest(time, requestPathHash, data);
    }
}
