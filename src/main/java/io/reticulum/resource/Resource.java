package io.reticulum.resource;

import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 *     The Resource class allows transferring arbitrary amounts
 *     of data over a link. It will automatically handle sequencing,
 *     compression, coordination and checksumming.
 */
@Data
@EqualsAndHashCode(of = "hash")
public class Resource {

    private Link link;
    private Path storagePath;
    private Instant lastActivity;
    private ResourceStatus status;
    private InputStream data;
    private Consumer<Resource> callback;
    private Consumer<Resource> progressCallback;
    private Object[] parts;

    private byte[] requestId;
    private byte[] hash;
    private byte[] randomHash;
    private byte[] originalHash;
    private byte[] hashmap;
    private byte[] hashmapRaw;

    private boolean compressed;
    private boolean encrypted;
    private boolean split;
    private boolean isResponse;
    private boolean initiator;
    private boolean waitingForHmu;
    private boolean receivingPart;

    private int segmentIndex;
    private int totalSegments;
    private int flags;
    private int uncompressedSize;
    private int totalParts;
    private int outstandingParts;
    private int window;
    private int windowMax;
    private int windowMin;
    private int windowFlexibility;
    private int hashmapHeight;
    private int size;
    private int totalSize;
    private int consecutiveCompletedHeight;

    private long receivedCount;

    private Resource() {

    }

    public Resource(byte[] packedRequest, Link link, byte[] requestId, boolean isResponse) {

    }

    public Resource(byte[] packedRequest, Link link, byte[] requestId, boolean isResponse, long timeout) {

    }

    public Resource(Link link, byte[] requestId) {
        this.link = link;
        this.requestId = requestId;
    }

    public static Resource accept(Packet packet, Consumer<Resource> callback) {
        return null;
    }

    public static Resource accept(
            Packet packet,
            Consumer<Resource> callback,
            Consumer<Resource> progressCallback,
            byte[] requestId
    ) {
        return null;
    }

    public void cancel() {

    }

    public int getTotalSize() {
        return 0;
    }

    public int getSize() {
        return 0;
    }

    public byte[] getHash() {
        return new byte[0];
    }

    public List<byte[]> getReqHashList() {
        return null;
    }

    public void request(byte[] plaintext) {

    }

    public void hashmapUpdatePacket(byte[] plaintext) {

    }

    public void receivePart(Packet packet) {

    }

    public void validateProof(byte[] data) {

    }

    public double getProgress() {
        return 0;
    }

    public void hashmapUpdate(int i, byte[] hashmapRaw) {

    }

    public void watchdogJob() {

    }
}
