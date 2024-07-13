package io.reticulum.storage.converter;

import io.reticulum.storage.entity.DestinationTable;
import io.reticulum.storage.entity.HopEntity;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.common.mapper.EntityConverter;
import org.dizitart.no2.common.mapper.NitriteMapper;

public class DestinationTableConverter implements EntityConverter<DestinationTable> {
    @Override
    public Class<DestinationTable> getEntityType() {
        return DestinationTable.class;
    }

    @Override
    public Document toDocument(DestinationTable destinationTable, NitriteMapper nitriteMapper) {
        var hop = nitriteMapper.tryConvert(destinationTable.getHop(), Document.class);

        return Document.createDocument()
                .put("destinationHash", destinationTable.getDestinationHash())
                .put("hop", hop);
    }

    @Override
    public DestinationTable fromDocument(Document document, NitriteMapper nitriteMapper) {
        var hop = (HopEntity) nitriteMapper.tryConvert(document.get("hop", Document.class), HopEntity.class);

        return DestinationTable.builder()
                .destinationHash(document.get("destinationHash", String.class))
                .hop(hop)
                .build();
    }
}
