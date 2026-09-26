package io.reticulum.link;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.SneakyThrows;
import org.msgpack.core.MessagePack;
import org.msgpack.value.ImmutableArrayValue;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import static java.util.Objects.isNull;

@Data
@AllArgsConstructor
public class UnpackedResponse {
    private byte[] requestId;
    private byte[] responseData;

    public ImmutableArrayValue toValue() {
        return ValueFactory.newArray(
                ValueFactory.newBinary(requestId),
                ValueFactory.newBinary(responseData)
        );
    }

    public static UnpackedResponse fromValue(ImmutableArrayValue value) {
        var requestId = value.get(0).asBinaryValue().asByteArray();

        return new UnpackedResponse(requestId, responseBytes(value.get(1)));
    }

    /**
     * A response generator may return any msgpack-serialisable value, not just
     * a byte string — the reference's own Request example returns a Python
     * {@code str}, which packs as msgpack <em>str</em> rather than <em>bin</em>.
     * Calling {@code asBinaryValue()} on that throws, so every response from a
     * handler returning a string, list or map used to fail here.
     *
     * <ul>
     *   <li>nil is delivered as null</li>
     *   <li>str and bin are delivered as their raw bytes</li>
     *   <li>anything else is re-encoded to msgpack, so a structured response is
     *       preserved for the caller to decode rather than being lost</li>
     * </ul>
     */
    @SneakyThrows
    private static byte[] responseBytes(final Value value) {
        if (isNull(value) || value.isNilValue()) {
            return null;
        }
        if (value.isRawValue()) {
            return value.asRawValue().asByteArray();
        }

        try (var packer = MessagePack.newDefaultBufferPacker()) {
            packer.packValue(value);

            return packer.toByteArray();
        }
    }
}
