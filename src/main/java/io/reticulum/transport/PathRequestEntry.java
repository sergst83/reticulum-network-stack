package io.reticulum.transport;

import io.reticulum.interfaces.ConnectionInterface;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * A path request this node is resolving on behalf of one or more peers.
 * <p>
 * Mirrors the reference's {@code discovery_path_requests} entry
 * ({@code RNS/Transport.py:192}), whose {@code requesting_interfaces} is a
 * <em>list</em>: while a recursive path request is in flight, further requests
 * for the same destination are batched onto it rather than each starting their
 * own search, and when the announce arrives every waiting requester is answered.
 * <p>
 * This field used to be a single interface, so on a transport node serving
 * several peers only the first requester ever received the path response; the
 * others waited out their own timeout and retried.
 */
@Data
@Builder
public class PathRequestEntry {
    private byte[] destinationHash;
    private Instant timeout;

    /** Every interface waiting on this request, in arrival order. */
    @Builder.Default
    private List<ConnectionInterface> requestingInterfaces = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Record another peer as waiting on this request.
     *
     * @return true if the interface was not already waiting
     */
    public boolean addRequestingInterface(ConnectionInterface connectionInterface) {
        if (connectionInterface == null || requestingInterfaces.contains(connectionInterface)) {
            return false;
        }

        return requestingInterfaces.add(connectionInterface);
    }
}
