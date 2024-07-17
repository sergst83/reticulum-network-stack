package io.reticulum.storage.entity;

import lombok.Builder;
import lombok.Data;
import org.dizitart.no2.repository.annotations.Entity;
import org.dizitart.no2.repository.annotations.Id;

import static io.reticulum.storage.Storage.PACKET_HASH_LIST;

@Entity(PACKET_HASH_LIST)
@Data
@Builder
public class PacketHash {
    @Id
    private String packetHash;

    private byte[] hash;
}
