package io.reticulum.storage;

import io.reticulum.identity.Identity;
import io.reticulum.identity.IdentityKnownDestination.DestinationData;
import io.reticulum.storage.converter.DestinationDataConverter;
import io.reticulum.storage.converter.TransportIdentityConverter;
import io.reticulum.storage.decorator.DestinationDataDecorator;
import io.reticulum.storage.entity.TransportIdentity;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.common.mapper.SimpleNitriteMapper;
import org.dizitart.no2.mvstore.MVStoreModule;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.dizitart.no2.common.util.Iterables.setOf;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Storage {

    private static volatile Storage STORAGE;

    private static final String DB_NAME = "jreticulum.db";

    //collection names
    public static final String TRANSPORT_IDENTITY = "transport_identity";
    public static final String PACKET_HASH_LIST = "packet_hashlist";
    public static final String DESTINATION_TABLE = "destination_table";
    public static final String TUNNELS = "tunnels";
    public static final String PACKET_CACHE = "cache";
    public static final String KNOWN_DESTINATIONS = "known_destinations";


    private final Nitrite db;

    public static Storage getInstance() {
        if (STORAGE == null) {
            throw new IllegalStateException("You have to call start method first to init storage instance");
        }

        return STORAGE;
    }

    public static Storage init(final Path storagePath) {
        Storage storage = STORAGE;
        if (storage == null) {
            synchronized (Storage.class) {
                storage = STORAGE;
                if (storage == null) {
                    var storeModule = MVStoreModule.withConfig()
                            .filePath(storagePath.resolve(DB_NAME).toString())
                            .compress(true)
                            .build();

                    var documentMapper = new SimpleNitriteMapper();
                    documentMapper.registerEntityConverter(new TransportIdentityConverter());
                    documentMapper.registerEntityConverter(new DestinationDataConverter());

                    var builder = Nitrite.builder()
                            .loadModule(storeModule)
                            .loadModule(() -> setOf(documentMapper));

                    STORAGE = storage = new Storage(builder.openOrCreate());
                }
            }
        }

        return storage;
    }

    public Identity getIdentity() {
        var transportIdentityRepo = db.getRepository(TransportIdentity.class);

        return Optional.ofNullable(transportIdentityRepo.find().firstOrNull())
                .map(TransportIdentity::getPrivateKey)
                .map(Identity::fromBytes)
                .orElse(null);
    }

    public void saveIdentity(@NonNull final Identity identity) {
        var transportIdentityRepo = db.getRepository(TransportIdentity.class);
        transportIdentityRepo.update(
                TransportIdentity.builder()
                        .identityHash(identity.getHexHash())
                        .privateKey(identity.getPrivateKey())
                        .build(),
                true
        );
    }

    public void saveKnownDestinations(Collection<DestinationData> knownDestinations) {
        if (CollectionUtils.isNotEmpty(knownDestinations)) {
            var repo = db.getRepository(new DestinationDataDecorator());
            knownDestinations.forEach(destinationData -> repo.update(destinationData, true));
        }
    }

    public Map<String, DestinationData> loadKnownDestinations() {
        return db.getRepository(new DestinationDataDecorator()).find()
                .toList()
                .stream()
                .collect(toMap(DestinationData::getDestinationHash, identity()));
    }
}
