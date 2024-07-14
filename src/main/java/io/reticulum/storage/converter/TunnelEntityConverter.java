package io.reticulum.storage.converter;

import io.reticulum.storage.entity.HopEntity;
import io.reticulum.storage.entity.TunnelEntity;
import org.apache.commons.collections4.MapUtils;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.common.mapper.EntityConverter;
import org.dizitart.no2.common.mapper.NitriteMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class TunnelEntityConverter implements EntityConverter<TunnelEntity> {
    @Override
    public Class<TunnelEntity> getEntityType() {
        return TunnelEntity.class;
    }

    @Override
    public Document toDocument(TunnelEntity entity, NitriteMapper nitriteMapper) {
        var tunnelPaths = new HashMap<String, Document>();
        if (MapUtils.isNotEmpty(entity.getTunnelPaths())) {
            for (String key : entity.getTunnelPaths().keySet()) {
                var doc = (Document) nitriteMapper.tryConvert(tunnelPaths.get(key), Document.class);
                tunnelPaths.put(key, doc);
            }
        } else {
            tunnelPaths = null;
        }

        return Document.createDocument()
                .put("tunnelIdHex", entity.getTunnelIdHex())
                .put("tunnelId", entity.getTunnelId())
                .put("interfaceHash", entity.getInterfaceHash())
                .put("tunnelPaths", tunnelPaths)
                .put("expires", entity.getExpires());
    }

    @Override
    public TunnelEntity fromDocument(Document document, NitriteMapper nitriteMapper) {
        var docMap = (Map<String, Document>) document.get("tunnelPaths");
        var tunnelPaths = new HashMap<String, HopEntity>();
        if (MapUtils.isNotEmpty(docMap)) {
            for (String key : docMap.keySet()) {
                var hopEntity = (HopEntity) nitriteMapper.tryConvert(docMap.get(key), HopEntity.class);
                tunnelPaths.put(key, hopEntity);
            }
        } else {
            tunnelPaths = null;
        }


        return TunnelEntity.builder()
                .tunnelIdHex(document.get("tunnelIdHex", String.class))
                .tunnelId(document.get("tunnelId", byte[].class))
                .interfaceHash(document.get("interfaceHash", byte[].class))
                .expires(document.get("expires", Instant.class))
                .tunnelPaths(tunnelPaths)
                .build();
    }
}
