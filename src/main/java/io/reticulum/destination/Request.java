package io.reticulum.destination;

import io.reticulum.identity.Identity;
import io.reticulum.utils.MsgPackUtils;
import lombok.Value;

import java.time.Instant;

import static java.util.Objects.isNull;

/**
 * An incoming request, as handed to a {@link ResponseGenerator}.
 * <p>
 * The reference passes the request data to the handler exactly as the
 * requester packed it, which may be bytes, nil or a structure such as a map.
 * {@link #getDataValue()} holds it unchanged; {@link #getData()} and
 * {@link #getDataObject()} are views for the two common cases.
 */
@Value
public class Request {
    String path;
    org.msgpack.value.Value dataValue;
    byte[] requestId;
    byte[] linkId;
    Identity remoteIdentity;
    Instant requestedAt;

    /**
     * The request data as bytes: raw bytes for a msgpack bin or str, the
     * msgpack encoding for any other value, null for nil. Mirrors how
     * {@code UnpackedResponse} delivers responses.
     */
    public byte[] getData() {
        if (isNull(dataValue) || dataValue.isNilValue()) {
            return null;
        }
        if (dataValue.isRawValue()) {
            return dataValue.asRawValue().asByteArray();
        }

        return MsgPackUtils.pack(dataValue);
    }

    /**
     * The request data as plain Java objects, decoded like {@code umsgpack}
     * does: a map arrives as a {@code Map} whose integer keys are {@code Long}.
     * See {@link MsgPackUtils#fromValue}.
     */
    public Object getDataObject() {
        return MsgPackUtils.fromValue(dataValue);
    }
}
