package io.reticulum.link;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.msgpack.value.ImmutableArrayValue;
import org.msgpack.value.ValueFactory;

import java.time.Instant;

import static java.util.Objects.isNull;

@Data
@AllArgsConstructor
public class UnpackedRequest {
    private Instant time;
    private byte[] requestPathHash;
    private byte[] data;

    /**
     * A request may legitimately carry no data — the reference packs
     * {@code [time, path_hash, None]} for that case (RNS/Link.py:487), and its
     * own Request example calls {@code request(path, data=None)}. Both
     * {@code newBinary(null)} and {@code asBinaryValue()} on a nil throw, so
     * the empty case is handled explicitly in each direction.
     */
    public ImmutableArrayValue toValue() {
        return ValueFactory.newArray(
                ValueFactory.newFloat(time.toEpochMilli() / 1000d),
                ValueFactory.newBinary(requestPathHash),
                isNull(data) ? ValueFactory.newNil() : ValueFactory.newBinary(data)
        );
    }

    public static UnpackedRequest fromValue(ImmutableArrayValue value) {
        var time = Instant.ofEpochMilli(Double.valueOf(value.get(0).asFloatValue().toDouble() * 1000).longValue());
        var requestPathHash = value.get(1).asBinaryValue().asByteArray();
        var dataValue = value.get(2);
        var data = isNull(dataValue) || dataValue.isNilValue() ? null : dataValue.asBinaryValue().asByteArray();

        return new UnpackedRequest(time, requestPathHash, data);
    }
}
