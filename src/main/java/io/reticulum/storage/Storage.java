package io.reticulum.storage;

import io.reticulum.identity.Identity;
import io.reticulum.identity.IdentityKnownDestination.DestinationData;
import io.reticulum.storage.converter.DestinationDataConverter;
import io.reticulum.storage.converter.DestinationTableConverter;
import io.reticulum.storage.converter.HopEntityConverter;
import io.reticulum.storage.converter.PacketCacheConverter;
import io.reticulum.storage.converter.PacketHashConverter;
import io.reticulum.storage.converter.TransportIdentityConverter;
import io.reticulum.storage.converter.TunnelEntityConverter;
import io.reticulum.storage.decorator.DestinationDataDecorator;
import io.reticulum.storage.entity.DestinationTable;
import io.reticulum.storage.entity.PacketCache;
import io.reticulum.storage.entity.PacketHash;
import io.reticulum.storage.entity.TransportIdentity;
import io.reticulum.storage.entity.TunnelEntity;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.common.mapper.SimpleNitriteMapper;
import org.dizitart.no2.exceptions.NitriteException;
import org.dizitart.no2.mvstore.MVStoreModule;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.dizitart.no2.common.util.Iterables.setOf;

@Slf4j
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

    /**
     * Latched once the packet cache is observed to be unavailable (e.g. the
     * underlying Nitrite/MVStore was closed after a disk-full write failure).
     * Used to log a single WARN instead of one per inbound packet — without
     * this guard a stuck store has been observed to spam thousands of
     * identical stack traces in {@code Transport.pathRequestHandler}.
     */
    private final AtomicBoolean packetCacheUnavailableLogged = new AtomicBoolean(false);

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
                    documentMapper.registerEntityConverter(new TunnelEntityConverter());

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

    /**
     * Clears the persisted packet hashlist collection. The packet hashlist is an
     * in-memory dedup window only (matching Python RNS) and is no longer persisted:
     * the previous {@code saveAllPacketHash()} upserted the current hashes but never
     * deleted aged-out ones, so the on-disk collection grew without bound (the union
     * of every packet hash ever seen) and drove jreticulum.db into multi-GB territory
     * on transport-enabled nodes. This drops any (possibly legacy) on-disk collection.
     */
    public void clearPacketHashList() {
        doInTransactionWithoutResult(__ -> db.getRepository(PacketHash.class).clear());
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

    public void savePacketCache(@NonNull final PacketCache packetCache) {
        try {
            doInTransactionWithoutResult(__ -> {
                var repo = db.getRepository(PacketCache.class);
                repo.update(packetCache, true);
            });
        } catch (NitriteException e) {
            // Packet cache is optional: if the underlying store is closed (e.g.
            // disk-full write failure earlier), continue without caching rather
            // than propagating to per-packet error sites.
            logPacketCacheUnavailable(e);
        }
    }

    public PacketCache getPacketCache(@NonNull final String packetHash) {
        try {
            return db.getRepository(PacketCache.class)
                    .getById(packetHash);
        } catch (NitriteException e) {
            // Treat a closed/unavailable store as a cache miss. Caller already
            // handles null gracefully (Transport.getCachedPacket returns null →
            // path request continues without cached reply).
            logPacketCacheUnavailable(e);
            return null;
        }
    }

    private void logPacketCacheUnavailable(NitriteException e) {
        if (packetCacheUnavailableLogged.compareAndSet(false, true)) {
            log.warn("Packet cache unavailable ({}: {}). Continuing without cache; further "
                            + "occurrences logged at DEBUG only.",
                    e.getClass().getSimpleName(), e.getMessage());
        } else {
            log.debug("Packet cache still unavailable: {}", e.getMessage());
        }
    }

    public void cleanPacketCache() {
        doInTransactionWithoutResult(__ -> {
            db.getRepository(PacketCache.class).clear();
        });
    }

    public void saveTunnelTable(Collection<TunnelEntity> tunnels) {
        if (CollectionUtils.isNotEmpty(tunnels)) {
            var repo = db.getRepository(TunnelEntity.class);
            doInTransactionWithoutResult(__ -> tunnels.forEach(tunnelEntity -> repo.update(tunnelEntity, true)));
        }
    }

    public Collection<TunnelEntity> getTunnelTables() {
        return db.getRepository(TunnelEntity.class).find().toList();
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
