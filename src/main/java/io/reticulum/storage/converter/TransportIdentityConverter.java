package io.reticulum.storage.converter;

import io.reticulum.storage.entity.TransportIdentity;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.common.mapper.EntityConverter;
import org.dizitart.no2.common.mapper.NitriteMapper;

public class TransportIdentityConverter implements EntityConverter<TransportIdentity> {

    @Override
    public Class<TransportIdentity> getEntityType() {
        return TransportIdentity.class;
    }

    @Override
    public Document toDocument(TransportIdentity entity, NitriteMapper nitriteMapper) {
        return Document.createDocument()
                .put("identityHash", entity.getIdentityHash())
                .put("privateKey", entity.getPrivateKey());
    }

    @Override
    public TransportIdentity fromDocument(Document document, NitriteMapper nitriteMapper) {
        return TransportIdentity.builder()
                .identityHash(document.get("identityHash", String.class))
                .privateKey(document.get("privateKey", byte[].class))
                .build();
    }
}
