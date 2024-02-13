package io.reticulum.storage.entity;

import lombok.Builder;
import lombok.Data;
import org.dizitart.no2.repository.annotations.Entity;
import org.dizitart.no2.repository.annotations.Id;

import static io.reticulum.storage.Storage.TRANSPORT_IDENTITY;

@Entity(TRANSPORT_IDENTITY)
@Data
@Builder
public class TransportIdentity {
    @Id
    private String identityHash;

    private byte[] privateKey;
}
