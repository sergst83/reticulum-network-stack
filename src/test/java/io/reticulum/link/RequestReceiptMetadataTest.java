package io.reticulum.link;

import io.reticulum.packet.PacketReceipt;
import io.reticulum.packet.PacketReceiptCallbacks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static io.reticulum.link.RequestReceiptStatus.DELIVERED;
import static io.reticulum.link.RequestReceiptStatus.FAILED;
import static io.reticulum.link.RequestReceiptStatus.READY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Covers the request-side half of the metadata channel: metadata delivered with
 * a response, and the max-response-size guard that rejects an oversized one.
 */
class RequestReceiptMetadataTest {

    private Link link;
    private List<RequestReceipt> pendingRequests;

    @BeforeEach
    void setUp() {
        pendingRequests = new CopyOnWriteArrayList<>();
        link = mock(Link.class);
        lenient().when(link.getPendingRequests()).thenReturn(pendingRequests);
    }

    /** The real PacketReceipt initialises its callbacks; a mock must be told to. */
    private static PacketReceipt stubPacketReceipt() {
        var packetReceipt = mock(PacketReceipt.class);
        lenient().when(packetReceipt.getTruncatedHash()).thenReturn(new byte[16]);
        lenient().when(packetReceipt.getCallbacks()).thenReturn(new PacketReceiptCallbacks());

        return packetReceipt;
    }

    private RequestReceipt newReceipt() {
        return new RequestReceipt(link, stubPacketReceipt(), null, null, null, 30_000L, 128);
    }

    private static Map<String, Object> metadata() {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("name", "release.whl");
        metadata.put("size", 123456);

        return metadata;
    }

    @Test
    @DisplayName("Response metadata is delivered to the receipt")
    void metadataDelivered() {
        var receipt = newReceipt();
        var metadata = metadata();

        receipt.responseReceived("payload".getBytes(), metadata);

        assertEquals(READY, receipt.getStatus());
        assertSame(metadata, receipt.getMetadata());
        assertEquals("payload", new String(receipt.getResponse()));
    }

    @Test
    @DisplayName("A response without metadata leaves it null")
    void metadataAbsent() {
        var receipt = newReceipt();

        receipt.responseReceived("payload".getBytes());

        assertEquals(READY, receipt.getStatus());
        assertNull(receipt.getMetadata());
    }

    @Test
    @DisplayName("The response callback sees the metadata")
    void callbackSeesMetadata() {
        var seen = new AtomicReference<Object>();
        var receipt = new RequestReceipt(link, stubPacketReceipt(), r -> seen.set(r.getMetadata()),
                null, null, 30_000L, 128);

        receipt.responseReceived("payload".getBytes(), metadata());

        assertEquals(metadata(), seen.get());
    }

    /**
     * The request size used to be written into the response size field, so a
     * receipt reported a response size before any response had arrived.
     */
    @Test
    @DisplayName("Request and response sizes are tracked separately")
    void requestSizeIsNotResponseSize() {
        var receipt = newReceipt();

        assertEquals(128, receipt.getRequestSize());
        assertEquals(0, receipt.getResponseSize(), "no response has arrived yet");
    }

    @Test
    @DisplayName("A rejected response fails the request and fires the failed callback")
    void rejectedResponseFailsRequest() {
        var failed = new AtomicReference<RequestReceipt>();
        var receipt = new RequestReceipt(link, stubPacketReceipt(), null, failed::set, null, 30_000L, 128);
        receipt.setStatus(DELIVERED);

        receipt.responseRejected();

        assertEquals(FAILED, receipt.getStatus());
        assertSame(receipt, failed.get());
        assertFalse(pendingRequests.contains(receipt), "a rejected request must be removed from pending");
    }

    @Test
    @DisplayName("Rejection only applies to a delivered, still-pending request")
    void rejectionIgnoredWhenNotDelivered() {
        var receipt = newReceipt();
        // status is SENT, not DELIVERED

        receipt.responseRejected();

        assertFalse(receipt.getStatus() == FAILED, "a request that was never delivered is not rejected here");
        assertTrue(pendingRequests.contains(receipt));
    }

    @Test
    @DisplayName("maxResponseSize defaults to unset")
    void maxResponseSizeDefaultsToNull() {
        assertNull(newReceipt().getMaxResponseSize());
    }
}
