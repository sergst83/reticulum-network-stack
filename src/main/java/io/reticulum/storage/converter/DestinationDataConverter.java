package io.reticulum.storage.converter;

import io.reticulum.identity.IdentityKnownDestination;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.common.mapper.EntityConverter;
import org.dizitart.no2.common.mapper.NitriteMapper;

public class DestinationDataConverter implements EntityConverter<IdentityKnownDestination.DestinationData> {
    @Override
    public Class<IdentityKnownDestination.DestinationData> getEntityType() {
        return IdentityKnownDestination.DestinationData.class;
    }

    @Override
    public Document toDocument(IdentityKnownDestination.DestinationData entity, NitriteMapper nitriteMapper) {
        return Document.createDocument()
                .put("destinationHash", entity.getDestinationHash())
                .put("timestamp", entity.getTimestamp())
                .put("packetHash", entity.getPackageHash())
                .put("publicKey", entity.getPublicKey())
                .put("appData", entity.getAppData());
    }

    @Override
    public IdentityKnownDestination.DestinationData fromDocument(Document document, NitriteMapper nitriteMapper) {
        if (document.size() == 0) {
            return new IdentityKnownDestination.DestinationData();
        }

        return new IdentityKnownDestination.DestinationData(
                document.get("destinationHash", String.class),
                document.get("timestamp", Long.class),
                document.get("packetHash", byte[].class),
                document.get("publicKey", byte[].class),
                document.get("appData", byte[].class)
        );
    }
}
