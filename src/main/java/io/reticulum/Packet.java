package io.reticulum;

import lombok.Getter;

@Getter
public class Packet {

    private byte packetType;
    private byte[] destinationHash;
    private byte[] data;

    private Integer rssi;
    private Integer snr;
    private byte[] transportId;
    private int hops;
    private Object receivingInterface;

    public byte[] getHash() {
        return new byte[0];
    }
}
