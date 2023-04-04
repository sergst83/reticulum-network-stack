package io.reticulum.resource;

import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import lombok.Data;

@Data
public class ResourceAdvertisement {

    private Link link;

    public static boolean isRequest(Packet packet) {
        return false;
    }

    public static boolean isResponse(Packet packet) {
        return false;
    }

    public static byte[] readRequestId(Packet packet) {
        return new byte[0];
    }

    public static int readSize(Packet packet) {
        return 0;
    }

    public static int readTransferSize(Packet packet) {
        return 0;
    }

    public static ResourceAdvertisement unpack(byte[] plaintext) {
        return new ResourceAdvertisement();
    }
}
