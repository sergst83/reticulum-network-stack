package io.reticulum.storage.entity;

import lombok.Builder;
import lombok.Data;
import org.dizitart.no2.repository.annotations.Entity;
import org.dizitart.no2.repository.annotations.Id;

import static io.reticulum.storage.Storage.DESTINATION_TABLE;

@Entity(DESTINATION_TABLE)
@Data
@Builder
public class DestinationTable {
    @Id
    private String destinationHash;

    private HopEntity hop;
}
