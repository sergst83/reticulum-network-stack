package io.reticulum.storage.converter;

import io.reticulum.storage.entity.HopEntity;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.common.mapper.EntityConverter;
import org.dizitart.no2.common.mapper.NitriteMapper;

import java.time.Instant;
import java.util.Arrays;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

public class HopEntityConverter implements EntityConverter<HopEntity> {
    @Override
    public Class<HopEntity> getEntityType() {
        return HopEntity.class;
    }

    @Override
    public Document toDocument(HopEntity entity, NitriteMapper nitriteMapper) {
        return Document.createDocument()
                .put("timestamp", entity.getTimestamp())
                .put("via", entity.getVia())
                .put("hops", entity.getHops())
                .put("expires", entity.getExpires())
                .put("randomBlobs", entity.getRandomBlobs().toArray(byte[][]::new))
                .put("interfaceHash", entity.getInterfaceHash())
                .put("packetHash", entity.getPacketHash());
    }

    @Override
    public HopEntity fromDocument(Document document, NitriteMapper nitriteMapper) {
        var randomBlobs = ofNullable(document.get("randomBlobs", byte[][].class))
                .map(bytes -> Arrays.stream(bytes).collect(toList()))
                .orElse(null);

        return HopEntity.builder()
                .timestamp(document.get("timestamp", Instant.class))
                .via(document.get("via", byte[].class))
                .hops(document.get("hops", int.class))
                .expires(document.get("expires", Instant.class))
                .randomBlobs(randomBlobs)
                .interfaceHash(document.get("interfaceHash", byte[].class))
                .packetHash(document.get("packetHash", byte[].class))
                .build();
    }
}
