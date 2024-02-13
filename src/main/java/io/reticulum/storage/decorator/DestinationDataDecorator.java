package io.reticulum.storage.decorator;

import io.reticulum.identity.IdentityKnownDestination;
import org.dizitart.no2.repository.EntityDecorator;
import org.dizitart.no2.repository.EntityId;
import org.dizitart.no2.repository.EntityIndex;

import java.util.List;

import static io.reticulum.storage.Storage.KNOWN_DESTINATIONS;

public class DestinationDataDecorator implements EntityDecorator<IdentityKnownDestination.DestinationData> {
    @Override
    public Class<IdentityKnownDestination.DestinationData> getEntityType() {
        return IdentityKnownDestination.DestinationData.class;
    }

    @Override
    public EntityId getIdField() {
        return new EntityId("destinationHash");
    }

    @Override
    public List<EntityIndex> getIndexFields() {
        return null;
    }

    @Override
    public String getEntityName() {
        return KNOWN_DESTINATIONS;
    }
}
