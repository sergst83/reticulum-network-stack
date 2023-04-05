package io.reticulum.resource;

import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;

@Data
@EqualsAndHashCode(of = "hash")
public class Resource {
    private ResourceStatus status;
    private InputStream data;
    private byte[] requestId;
    private byte[] hash;
    private Consumer<Resource> callback;

    public Resource(byte[] packedRequest, Link link, byte[] requestId, boolean isResponse) {

    }

    public Resource(byte[] packedRequest, Link link, byte[] requestId, boolean isResponse, long timeout) {

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
}
