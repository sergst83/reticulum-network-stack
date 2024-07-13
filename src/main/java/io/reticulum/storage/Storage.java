package io.reticulum.storage;

import io.reticulum.identity.Identity;
import io.reticulum.identity.IdentityKnownDestination.DestinationData;
import io.reticulum.storage.converter.DestinationDataConverter;
import io.reticulum.storage.converter.DestinationTableConverter;
import io.reticulum.storage.converter.HopEntityConverter;
import io.reticulum.storage.converter.PacketCacheConverter;
import io.reticulum.storage.converter.PacketHashConverter;
import io.reticulum.storage.converter.TransportIdentityConverter;
import io.reticulum.storage.decorator.DestinationDataDecorator;
import io.reticulum.storage.entity.DestinationTable;
import io.reticulum.storage.entity.PacketCache;
import io.reticulum.storage.entity.PacketHash;
import io.reticulum.storage.entity.TransportIdentity;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.common.mapper.SimpleNitriteMapper;
import org.dizitart.no2.mvstore.MVStoreModule;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.reticulum.constant.TransportConstant.HASHLIST_MAXSIZE;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.dizitart.no2.collection.FindOptions.orderBy;
import static org.dizitart.no2.common.SortOrder.Descending;
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

    public static Storage init(@NonNull final Path storagePath) {
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
                    documentMapper.registerEntityConverter(new PacketHashConverter());
                    documentMapper.registerEntityConverter(new DestinationTableConverter());
                    documentMapper.registerEntityConverter(new HopEntityConverter());
                    documentMapper.registerEntityConverter(new PacketCacheConverter());

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
        doInTransactionWithoutResult(__ ->
                transportIdentityRepo.update(
                        TransportIdentity.builder()
                                .identityHash(identity.getHexHash())
                                .privateKey(identity.getPrivateKey())
                                .build(),
                        true
                )
        );
    }

    public void saveKnownDestinations(final Collection<DestinationData> knownDestinations) {
        if (CollectionUtils.isNotEmpty(knownDestinations)) {
            var repo = db.getRepository(new DestinationDataDecorator());
            doInTransactionWithoutResult(__ -> knownDestinations.forEach(destinationData -> repo.update(destinationData, true)));
        }
    }

    public Map<String, DestinationData> loadKnownDestinations() {
        return db.getRepository(new DestinationDataDecorator()).find()
                .toList()
                .stream()
                .collect(toMap(DestinationData::getDestinationHash, identity()));
    }

    public void saveAllPacketHash(Map<String, byte[]> packetHashes) {
        var repo = db.getRepository(PacketHash.class);
        if (MapUtils.isNotEmpty(packetHashes)) {
            doInTransactionWithoutResult(__ -> packetHashes.forEach((hex, bytes) -> repo.update(PacketHash.builder().packetHash(hex).hash(bytes).build(), true)));
        } else {
            repo.clear();
        }
    }

    public void saveDestinationTables(Collection<DestinationTable> destinationTables) {
        if (CollectionUtils.isNotEmpty(destinationTables)) {
            var repo = db.getRepository(DestinationTable.class);
            doInTransactionWithoutResult(__ -> destinationTables.forEach(destinationTable -> repo.update(destinationTable, true)));
        }
    }

    public Collection<DestinationTable> getDestinationTables() {
        return db.getRepository(DestinationTable.class).find().toList();
    }

    public Map<String, byte[]> loadAllPacketHash() {
        return db.getRepository(PacketHash.class).find()
                .toList()
                .stream()
                .collect(toMap(PacketHash::getPacketHash, PacketHash::getHash));
    }

    public Map<String, byte[]> trimPacketHashList() {
        var repo = db.getRepository(PacketHash.class);
        var toSave = repo.find(orderBy("timestamp", Descending).limit(HASHLIST_MAXSIZE)).toList();
        repo.clear();

        return doInTransaction(() -> {
            repo.insert(toSave.toArray(PacketHash[]::new));

            return toSave.stream().collect(toMap(PacketHash::getPacketHash, PacketHash::getHash));
        });
    }

    public void savePacketCache(@NonNull final PacketCache packetCache) {
        doInTransactionWithoutResult(__ -> {
            var repo = db.getRepository(PacketCache.class);
            repo.update(packetCache, true);
        });
    }

    public PacketCache getPacketCache(@NonNull final String packetHash) {
        return db.getRepository(PacketCache.class)
                .getById(packetHash);
    }

    private <Result> Result doInTransaction(Supplier<Result> supplier) {
        try (var session = db.createSession()) {
            try(var transaction = session.beginTransaction()) {
                var result = supplier.get();
                transaction.commit();

                return result;
            }
        }
    }

    private void doInTransactionWithoutResult(Consumer<Void> consumer) {
        doInTransaction((Supplier<Void>) () -> {
            consumer.accept(null);
            return null;
        });
    }
}
