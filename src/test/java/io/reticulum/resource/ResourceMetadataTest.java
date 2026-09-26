package io.reticulum.resource;

import io.reticulum.destination.DestinationType;
import io.reticulum.link.Link;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static io.reticulum.constant.LinkConstant.MDU;
import static io.reticulum.constant.ResourceConstant.RANDOM_HASH_SIZE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Covers the resource metadata channel: a msgpack-encoded object carried in
 * front of the payload behind a 3-byte big-endian length prefix
 * ({@code RNS/Resource.py:262-268} and {@code :709-717}).
 * <p>
 * {@link #framingMatchesReference()} asserts against bytes produced by Python
 * RNS 1.5.2 for the same object, so this pins the wire format rather than just
 * Java's self-consistency.
 */
class ResourceMetadataTest {

    /**
     * {"name": "release.whl", "size": 123456, "code": 0} framed by Python RNS
     * 1.5.2 as {@code struct.pack(">I", len)[1:] + umsgpack.packb(metadata)}.
     */
    private static final String REFERENCE_FRAMED =
            "00002283a46e616d65ab72656c656173652e77686ca473697a65ce0001e240a4636f646500";

    private static Map<String, Object> referenceMetadata() {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("name", "release.whl");
        metadata.put("size", 123456);
        metadata.put("code", 0);

        return metadata;
    }

    private Link link;

    private static byte[] payload(int size) {
        var data = new byte[size];
        new Random(20260905L).nextBytes(data);

        return data;
    }

    @BeforeEach
    void setUp() {
        link = mock(Link.class);
        lenient().when(link.getTrafficTimeoutFactor()).thenReturn(6);
        lenient().when(link.getRtt()).thenReturn(100L);
        lenient().when(link.getHash()).thenReturn(new byte[16]);
        lenient().when(link.getLinkId()).thenReturn(new byte[16]);
        lenient().when(link.getType()).thenReturn(DestinationType.LINK);
        lenient().when(link.getMtu()).thenReturn(500);
        lenient().when(link.encrypt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(link.readyForNewResource()).thenReturn(false);
    }

    private static Object invoke(String name, Class<?> argType, Object arg) throws Exception {
        Method method = Resource.class.getDeclaredMethod(name, argType);
        method.setAccessible(true);
        try {
            return method.invoke(null, arg);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    @DisplayName("Framing is byte-identical to Python RNS 1.5.2")
    void framingMatchesReference() throws Exception {
        var framed = (byte[]) invoke("frameMetadata", Object.class, referenceMetadata());

        assertEquals(REFERENCE_FRAMED, Hex.encodeHexString(framed));
    }

    @Test
    @DisplayName("A Python-framed metadata block parses back to the original object")
    void parsesReferenceFraming() throws Exception {
        var framed = Hex.decodeHex(REFERENCE_FRAMED);

        var declared = (int) invoke("declaredMetadataSize", byte[].class, framed);
        assertEquals(framed.length - 3, declared);

        var packed = new byte[declared];
        System.arraycopy(framed, 3, packed, 0, declared);
        var decoded = invoke("unpackMetadata", byte[].class, packed);

        assertEquals(referenceMetadata(), decoded);
    }

    @Test
    @DisplayName("The 3-byte length prefix is big-endian")
    void lengthPrefixIsBigEndian() throws Exception {
        var framed = (byte[]) invoke("frameMetadata", Object.class, referenceMetadata());

        assertEquals(0x00, framed[0] & 0xFF);
        assertEquals(0x00, framed[1] & 0xFF);
        assertEquals(0x22, framed[2] & 0xFF, "34 bytes of packed metadata");
        assertEquals(3 + 0x22, framed.length);
    }

    @Test
    @DisplayName("Metadata exceeding METADATA_MAX_SIZE is rejected")
    void oversizedMetadataRejected() {
        // A string long enough that its msgpack encoding passes the 16 MiB cap
        var huge = "x".repeat(17 * 1024 * 1024);

        assertThrows(IllegalArgumentException.class,
                () -> invoke("frameMetadata", Object.class, huge));
    }

    @Test
    @DisplayName("A resource with metadata reports it and sizes the transfer for it")
    void resourceCarriesMetadata() throws Exception {
        var data = payload(MDU * 3);
        var framed = (byte[]) invoke("frameMetadata", Object.class, referenceMetadata());

        var resource = new Resource(data, link, referenceMetadata(), null, null,
                new byte[16], true, 30_000L, false, null, true);

        assertTrue(resource.isHasMetadata());
        assertEquals(framed.length, resource.getMetadataSize());
        assertArrayEquals(framed, resource.getMetadata());
        assertEquals(data.length + framed.length, resource.getTotalSize(),
                "total size must include the framed metadata");
    }

    @Test
    @DisplayName("A resource without metadata carries none")
    void resourceWithoutMetadata() {
        var resource = new Resource(payload(MDU * 3), link, new byte[16], true, 30_000L);

        assertFalse(resource.isHasMetadata());
        assertEquals(0, resource.getMetadataSize());
        assertEquals(0, resource.getMetadata().length);
    }

    /**
     * The advertisement's {@code x} flag is how the receiver learns to expect a
     * metadata prefix. It was never set from the resource, so metadata would
     * have been transmitted but never parsed back out.
     */
    @Test
    @DisplayName("The advertisement metadata flag reflects the resource")
    void advertisementCarriesMetadataFlag() {
        var withMetadata = new Resource(payload(MDU * 3), link, referenceMetadata(), null, null,
                new byte[16], true, 30_000L, false, null, true);
        var withoutMetadata = new Resource(payload(MDU * 3), link, new byte[16], true, 30_000L);

        assertTrue(new ResourceAdvertisement(withMetadata).isX(), "x flag must be set");
        assertFalse(new ResourceAdvertisement(withoutMetadata).isX());

        // and it must survive a pack/unpack round trip
        var repacked = ResourceAdvertisement.unpack(new ResourceAdvertisement(withMetadata).pack(0));
        assertTrue(repacked.isX());
    }

    @Test
    @DisplayName("Metadata is prepended inside the hashed payload, not sent separately")
    void metadataIsInsideThePayload() throws Exception {
        var data = payload(MDU * 3);
        var framed = (byte[]) invoke("frameMetadata", Object.class, referenceMetadata());

        // auto-compress off, and the mocked link's encrypt is identity, so the
        // transmitted bytes are the random-hash prefix followed by the payload
        var resource = new Resource(data, link, referenceMetadata(), null, null,
                new byte[16], true, 30_000L, false, null, true);

        var expectedPayloadLength = RANDOM_HASH_SIZE + framed.length + data.length;
        assertEquals(expectedPayloadLength, resource.getSize(),
                "transmitted size must cover random hash + metadata + data");
    }
}
