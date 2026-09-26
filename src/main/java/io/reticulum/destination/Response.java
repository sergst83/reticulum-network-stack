package io.reticulum.destination;

import lombok.Getter;

import java.io.File;

import static java.util.Objects.isNull;
import static java.util.Objects.requireNonNull;

/**
 * The value a request handler returns.
 * <p>
 * Mirrors the two shapes the reference implementation accepts from a response
 * generator ({@code RNS/Link.py:836-851}):
 * <ul>
 *   <li>a plain value, packed together with the request ID and sent as a packet
 *       or, if oversized, as a resource; or</li>
 *   <li>a file plus optional metadata, sent as a resource whose metadata
 *       describes the file.</li>
 * </ul>
 * Metadata is only meaningful on a file response — that is the only case in
 * which the reference transmits it.
 */
@Getter
public final class Response {

    private final byte[] data;
    private final File file;
    private final Object metadata;

    private Response(final byte[] data, final File file, final Object metadata) {
        this.data = data;
        this.file = file;
        this.metadata = metadata;
    }

    /**
     * A response carrying a byte payload.
     */
    public static Response of(final byte[] data) {
        return new Response(requireNonNull(data, "Response data cannot be null"), null, null);
    }

    /**
     * A file response with no metadata.
     */
    public static Response ofFile(final File file) {
        return ofFile(file, null);
    }

    /**
     * A file response carrying metadata describing it. The metadata is
     * msgpack-encoded and delivered to the requester via
     * {@code RequestReceipt.getMetadata()}.
     */
    public static Response ofFile(final File file, final Object metadata) {
        return new Response(null, requireNonNull(file, "Response file cannot be null"), metadata);
    }

    /**
     * @return true if this response streams a file rather than a byte payload
     */
    public boolean isFileResponse() {
        return !isNull(file);
    }
}
