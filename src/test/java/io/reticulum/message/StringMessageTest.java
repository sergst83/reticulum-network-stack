package io.reticulum.message;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Wire parity for {@link StringMessage} against {@code Examples/Channel.py}.
 * <p>
 * The reference packs {@code (self.data, self.timestamp)} where {@code data} is
 * a Python {@code str}, so the payload is msgpack <em>str</em>. This side packed
 * <em>bin</em> and unpacked with {@code asBinaryValue()}, which throws on a str —
 * so every such message from a reference peer failed, and every one sent to it
 * arrived as {@code bytes} instead of a string. Same shape as the
 * {@code UnpackedResponse} bug found in step 4.
 * <p>
 * Vectors produced by {@code RNS.vendor.umsgpack} in the 1.5.2 checkout.
 */
class StringMessageTest {

    /** umsgpack.packb(("Hi there", datetime(2026,9,6,12,0,0,123456, tz=utc))) */
    private static final String REFERENCE = "92a84869207468657265d7ff1d6f28006a9d55c0";

    /** umsgpack.packb((None, <same timestamp>)) */
    private static final String REFERENCE_NIL_DATA = "92c0d7ff1d6f28006a9d55c0";

    private static final Instant TIMESTAMP = Instant.ofEpochSecond(1788696000L, 123456000L);

    @Test
    @DisplayName("pack produces exactly the reference's bytes")
    void packMatchesReference() {
        var message = new StringMessage("Hi there".getBytes(UTF_8));
        message.setTimestamp(TIMESTAMP);

        assertEquals(REFERENCE, Hex.encodeHexString(message.pack()));
    }

    @Test
    @DisplayName("unpack reads the reference's bytes")
    void unpackReadsReference() throws Exception {
        var message = new StringMessage();
        message.unpack(Hex.decodeHex(REFERENCE));

        assertArrayEquals("Hi there".getBytes(UTF_8), message.getData());
        assertEquals(TIMESTAMP, message.getTimestamp());
    }

    @Test
    @DisplayName("unpack still accepts a bin payload")
    void unpackAcceptsBinaryPayload() throws Exception {
        // Anything already on the wire from an older Java peer packs bin. Both
        // are raw values, so accepting either costs nothing.
        var binary = "92c4084869207468657265d7ff1d6f28006a9d55c0";
        var message = new StringMessage();
        message.unpack(Hex.decodeHex(binary));

        assertArrayEquals("Hi there".getBytes(UTF_8), message.getData());
    }

    @Test
    @DisplayName("an empty message packs nil rather than throwing")
    void emptyMessagePacksNil() {
        // The no-argument constructor exists so the channel can build an empty
        // instance to unpack into; ValueFactory.newBinary(null) threw outright.
        var message = new StringMessage();
        message.setTimestamp(TIMESTAMP);

        assertEquals(REFERENCE_NIL_DATA, Hex.encodeHexString(message.pack()));
    }

    @Test
    @DisplayName("nil payload round-trips back to null")
    void nilPayloadRoundTrips() throws Exception {
        var message = new StringMessage();
        message.unpack(Hex.decodeHex(REFERENCE_NIL_DATA));

        assertNull(message.getData());
        assertEquals(TIMESTAMP, message.getTimestamp());
    }

    @Test
    @DisplayName("a message round-trips through its own pack and unpack")
    void roundTrip() {
        var sent = new StringMessage("hello mesh".getBytes(UTF_8));
        var received = new StringMessage();
        received.unpack(sent.pack());

        assertArrayEquals(sent.getData(), received.getData());
        assertEquals(sent.getTimestamp().getEpochSecond(), received.getTimestamp().getEpochSecond());
    }
}
