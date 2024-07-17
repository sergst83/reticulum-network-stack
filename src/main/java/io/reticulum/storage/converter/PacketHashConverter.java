package io.reticulum.storage.converter;

import io.reticulum.storage.entity.PacketHash;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.common.mapper.EntityConverter;
import org.dizitart.no2.common.mapper.NitriteMapper;

import java.time.Instant;

public class PacketHashConverter implements EntityConverter<PacketHash> {
    @Override
    public Class<PacketHash> getEntityType() {
        return PacketHash.class;
    }

    @Override
    public Document toDocument(PacketHash entity, NitriteMapper nitriteMapper) {
        return Document.createDocument()
                .put("packetHash", entity.getPacketHash())
                .put("hash", entity.getHash())
                .put("timestamp", Instant.now().toEpochMilli());
    }

    @Override
    public PacketHash fromDocument(Document document, NitriteMapper nitriteMapper) {
        return PacketHash.builder()
                .packetHash(document.get("packetHash", String.class))
                .hash(document.get("hash", byte[].class))
                .build();
    }
}
