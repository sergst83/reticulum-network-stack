package io.reticulum.storage.entity;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class HopEntity implements Serializable {

    private Instant timestamp; //0
    private byte[] via; //1
    private int hops; //2
    private Instant expires; //3
    private List<byte[]> randomBlobs; //4
    private byte[] interfaceHash; //5
    private byte[] packetHash; //6

    @Data
    @Builder
    public static class PacketEntity implements Serializable {
        private byte[] raw;
        private int hops;
        private String receivingInterfaceName;
    }
}
