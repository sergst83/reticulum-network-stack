package io.reticulum.link;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.msgpack.value.ImmutableArrayValue;
import org.msgpack.value.ValueFactory;

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
        var responseData = value.get(1).asBinaryValue().asByteArray();

        return new UnpackedResponse(requestId, responseData);
    }
}
