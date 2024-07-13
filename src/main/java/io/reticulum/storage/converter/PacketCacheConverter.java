package io.reticulum.storage.converter;

import io.reticulum.storage.entity.PacketCache;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.common.mapper.EntityConverter;
import org.dizitart.no2.common.mapper.NitriteMapper;

public class PacketCacheConverter implements EntityConverter<PacketCache> {

    @Override
    public Class<PacketCache> getEntityType() {
        return PacketCache.class;
    }

    @Override
    public Document toDocument(PacketCache entity, NitriteMapper nitriteMapper) {
        return Document.createDocument()
                .put("packetHash", entity.getPacketHash())
                .put("raw", entity.getRaw())
                .put("interfaceName", entity.getInterfaceName());
    }

    @Override
    public PacketCache fromDocument(Document document, NitriteMapper nitriteMapper) {
        return PacketCache.builder()
                .packetHash(document.get("packetHash", String.class))
                .raw(document.get("raw", byte[].class))
                .interfaceName(document.get("interfaceName", String.class))
                .build();
    }
}
