package io.reticulum.storage.entity;

import lombok.Builder;
import lombok.Data;
import org.dizitart.no2.repository.annotations.Entity;
import org.dizitart.no2.repository.annotations.Id;

import java.time.Instant;
import java.util.Map;

import static io.reticulum.storage.Storage.TUNNELS;

@Data
@Builder
@Entity(TUNNELS)
public class TunnelEntity {
    @Id
    private String tunnelIdHex;

    private byte[] tunnelId; //0
    private byte[] interfaceHash; // 1
    private Map<String, HopEntity> tunnelPaths; //2
    private Instant expires; //3
}
