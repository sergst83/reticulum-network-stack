package io.reticulum.resource;

import io.reticulum.destination.DestinationType;
import io.reticulum.link.Link;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import org.apache.commons.compress.compressors.CompressorStreamFactory;

import static io.reticulum.constant.LinkConstant.MDU;
import static io.reticulum.constant.ResourceConstant.MAX_EFFICIENT_SIZE;
import static org.apache.commons.compress.compressors.CompressorStreamFactory.BZIP2;
import static org.apache.commons.compress.compressors.CompressorStreamFactory.BZIP2;
import static io.reticulum.constant.ResourceConstant.MAPHASH_LEN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the request/response {@link Resource} constructors used by
 * {@code Link.request()} and {@code Link.handleRequest()} for payloads that
 * exceed the link MDU.
 * <p>
 * Both constructors previously had empty bodies, so an over-MDU request or
 * response produced a Resource that never advertised, never transferred and
 * never concluded — the peer simply timed out. These tests assert the resource
 * is actually initialised.
 * <p>
 * The link is mocked so the test stays a unit test: {@code readyForNewResource()}
 * returns false, which parks the advertisement job in its QUEUED wait loop and
 * keeps it away from the Transport singleton.
 */
class ResourceRequestResponseTest {

    private Link link;

    /** Comfortably larger than the link MDU, so this is the resource path. */
    private static final byte[] PAYLOAD = buildPayload(MDU * 5);

    private static byte[] buildPayload(int size) {
        // Seeded random, so the payload is deterministic but incompressible —
        // a regular pattern would be squashed by the BZIP2 auto-compression into
        // a single part and defeat the point of the test.
        var payload = new byte[size];
        new Random(20260905L).nextBytes(payload);

        return payload;
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
        // Identity "encryption" keeps sizes predictable; Resource only needs bytes back
        lenient().when(link.encrypt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        // Parks the advertise job in its QUEUED loop, so it never reaches Transport
        lenient().when(link.readyForNewResource()).thenReturn(false);
    }

    @Test
    @DisplayName("Request constructor initialises the resource instead of no-opping")
    void requestConstructorInitialisesResource() {
        var requestId = "req-id-0123456789".getBytes(StandardCharsets.UTF_8);

        var resource = new Resource(PAYLOAD, link, requestId, false, 30_000L);

        assertResourceInitialised(resource);
        assertArrayEquals(requestId, resource.getRequestId());
        assertFalse(resource.isResponse());
        assertEquals(30_000L, resource.getTimeout(), "explicit timeout must be honoured");
    }

    @Test
    @DisplayName("Response constructor initialises the resource and derives its timeout from RTT")
    void responseConstructorInitialisesResource() {
        var requestId = "req-id-0123456789".getBytes(StandardCharsets.UTF_8);

        var resource = new Resource(PAYLOAD, link, requestId, true);

        assertResourceInitialised(resource);
        assertArrayEquals(requestId, resource.getRequestId());
        assertTrue(resource.isResponse());
        // RNS/Resource.py: timeout = link.rtt * link.traffic_timeout_factor
        assertEquals(100L * 6, resource.getTimeout(), "timeout must fall back to RTT * traffic timeout factor");
    }

    @Test
    @DisplayName("Both constructors agree with the full constructor they delegate to")
    void delegationMatchesFullConstructor() {
        var requestId = "req-id-0123456789".getBytes(StandardCharsets.UTF_8);

        var viaShortCtor = new Resource(PAYLOAD, link, requestId, true);
        var viaFullCtor = new Resource(PAYLOAD, link, null, null, requestId, true, null, true, null, true);

        assertEquals(viaFullCtor.getTotalSize(), viaShortCtor.getTotalSize());
        assertEquals(viaFullCtor.getTotalParts(), viaShortCtor.getTotalParts());
        assertEquals(viaFullCtor.getTotalSegments(), viaShortCtor.getTotalSegments());
        assertEquals(viaFullCtor.getSegmentIndex(), viaShortCtor.getSegmentIndex());
        assertEquals(viaFullCtor.isSplit(), viaShortCtor.isSplit());
        assertEquals(viaFullCtor.isResponse(), viaShortCtor.isResponse());
        assertEquals(viaFullCtor.getTimeout(), viaShortCtor.getTimeout());
    }

    /**
     * The payload is split across multiple parts, which is the whole point of
     * sending it as a resource rather than a packet.
     */
    @Test
    @DisplayName("An over-MDU payload is split into transferable parts")
    void payloadIsSplitIntoParts() {
        var resource = new Resource(PAYLOAD, link, new byte[16], false, 30_000L);

        assertTrue(resource.getParts() > 1,
                "an over-MDU payload must produce more than one part, got " + resource.getParts()
                + " (payload=" + PAYLOAD.length + " size=" + resource.getSize()
                + " totalSize=" + resource.getTotalSize() + " compressed=" + resource.isCompressed() + ")");

        // Every part contributes one map hash, and the hashmap is their concatenation
        assertEquals(resource.getParts() * MAPHASH_LEN, resource.getHashmap().length);
    }

    /**
     * {@code ResourceAdvertisement} reads the part count from {@code getTotalParts()}.
     * The sender used never to set it, so every outgoing advertisement declared
     * zero parts (RNS/Resource.py:438 sets it alongside the hashmap entry count).
     */
    @Test
    @DisplayName("Sender-side totalParts is populated, so the advertisement declares a real part count")
    void advertisementDeclaresPartCount() {
        var resource = new Resource(PAYLOAD, link, new byte[16], true);

        assertTrue(resource.getTotalParts() > 1,
                "totalParts must be set on the sending side, got " + resource.getTotalParts());
        assertEquals(resource.getTotalParts(), new ResourceAdvertisement(resource).getParts(),
                "advertisement part count must match the resource");
    }

    private void assertResourceInitialised(Resource resource) {
        assertNotNull(resource.getHash(), "hash must be computed");
        assertNotNull(resource.getRandomHash(), "random hash must be computed");
        assertNotNull(resource.getExpectedProof(), "expected proof must be computed");
        assertNotNull(resource.getHashmap(), "hashmap must be computed");
        assertTrue(resource.getParts() > 0, "parts must be built");
        assertTrue(resource.getSize() > 0, "size must be set");
        assertTrue(resource.getTotalSize() > 0, "total size must be set");
        assertTrue(resource.isInitiator(), "a locally created resource is the initiator");
        assertEquals(1, resource.getSegmentIndex());
        assertEquals(1, resource.getTotalSegments());
        assertFalse(resource.isSplit());
        assertFalse(Arrays.equals(new byte[resource.getHash().length], resource.getHash()),
                "hash must not be all zeroes");
    }

    /**
     * The BZIP2 compressor used to be read before it was closed, so
     * {@code compressedData} came back as a bare 3-byte stream header for any
     * input. Being shorter than any payload it always won the size comparison,
     * and the resource shipped that stub instead of the data — silent corruption
     * of every compressible resource transfer.
     */
    @Test
    @DisplayName("Compressed resource data is complete and round-trips through BZIP2")
    void compressionIsNotTruncated() throws Exception {
        // Highly repetitive, so compression genuinely wins
        var compressible = new byte[MDU * 20];
        for (int i = 0; i < compressible.length; i++) {
            compressible[i] = (byte) ('A' + (i % 8));
        }

        var resource = new Resource(compressible, link, new byte[16], false, 30_000L);

        assertTrue(resource.isCompressed(), "repetitive payload must compress");

        var compressed = resource.getCompressedData();
        assertNotNull(compressed);
        assertTrue(compressed.length > 4,
                "compressed data must be more than a BZIP2 header, got " + compressed.length + " bytes");
        assertTrue(compressed.length < compressible.length,
                "compression must actually reduce size");

        // The compressed bytes must be a complete, decompressible BZIP2 stream
        byte[] restored;
        try (var bais = new java.io.ByteArrayInputStream(compressed);
             var decompressor = new CompressorStreamFactory().createCompressorInputStream(BZIP2, bais)) {
            restored = decompressor.readAllBytes();
        }

        assertArrayEquals(compressible, restored, "decompressed data must equal the original payload");
    }

    @Test
    @DisplayName("Incompressible data is sent uncompressed")
    void incompressibleDataIsNotCompressed() {
        var resource = new Resource(PAYLOAD, link, new byte[16], false, 30_000L);

        assertFalse(resource.isCompressed(),
                "random data must not be flagged as compressed");
        // random hash prefix + payload
        assertEquals(PAYLOAD.length + 4, resource.getSize());
    }

    // ── P0.3: size limits and segmentation ────────────────────────────────

    /**
     * A file larger than {@code MAX_EFFICIENT_SIZE} is split into segments.
     * The segment count used to be computed with a multiplication where
     * RNS/Resource.py:307 has an integer division, which overflowed int and
     * produced a nonsensical count for every split resource.
     */
    @Test
    @DisplayName("Oversized input is split into the same segment count as the reference")
    void oversizedInputSegmentCount() throws Exception {
        // Just past the limit: the reference computes ((size-1)/MAX_EFFICIENT_SIZE)+1 = 2
        var file = Files.createTempFile("reticulum-resource-", ".bin");
        try {
            var payload = new byte[MAX_EFFICIENT_SIZE + 1];
            new Random(20260905L).nextBytes(payload);
            Files.write(file, payload);

            var resource = new Resource(file.toFile(), link, null, 1, null, null);

            assertTrue(resource.isSplit(), "input past MAX_EFFICIENT_SIZE must be split");
            assertEquals(2, resource.getTotalSegments(),
                    "segment count must match ((size-1)/MAX_EFFICIENT_SIZE)+1");
            assertEquals(1, resource.getSegmentIndex());
            assertEquals(payload.length, resource.getTotalSize());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("Input at exactly MAX_EFFICIENT_SIZE is a single unsplit segment")
    void inputAtLimitIsNotSplit() throws Exception {
        var file = Files.createTempFile("reticulum-resource-", ".bin");
        try {
            var payload = new byte[MAX_EFFICIENT_SIZE];
            new Random(20260905L).nextBytes(payload);
            Files.write(file, payload);

            var resource = new Resource(file.toFile(), link, null, 1, null, null);

            assertFalse(resource.isSplit(), "input at exactly the limit must not be split");
            assertEquals(1, resource.getTotalSegments());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
