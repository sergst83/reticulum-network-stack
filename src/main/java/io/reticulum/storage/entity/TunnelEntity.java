package io.reticulum.storage.entity;

import io.reticulum.storage.Storage;
import lombok.Builder;
import lombok.Data;
import org.dizitart.no2.repository.annotations.Entity;
import org.dizitart.no2.repository.annotations.Id;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@Entity(Storage.TUNNELS)
public class TunnelEntity {
    @Id
    private String tunnelIdHex;

    private byte[] tunnelId; //0
    private String interfaceName; // 1
    private Map<String, HopEntity> tunnelPaths; //2
    private Instant expires; //3
}
