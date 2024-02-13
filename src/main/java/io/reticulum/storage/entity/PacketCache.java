package io.reticulum.storage.entity;

import lombok.Builder;
import lombok.Data;
import org.dizitart.no2.repository.annotations.Entity;
import org.dizitart.no2.repository.annotations.Id;

import static io.reticulum.storage.Storage.PACKET_CACHE;

@Entity(value = PACKET_CACHE)
@Data
@Builder
public class PacketCache {

    @Id
    private String packetHash;

    private byte[] raw;
    private String interfaceName;
}
