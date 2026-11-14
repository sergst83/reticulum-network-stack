package io.reticulum.storage;

import io.reticulum.storage.converter.HopEntityConverter;
import io.reticulum.storage.converter.TunnelEntityConverter;
import io.reticulum.storage.entity.HopEntity;
import io.reticulum.storage.entity.TunnelEntity;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.common.mapper.SimpleNitriteMapper;
import org.dizitart.no2.mvstore.MVStoreModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.dizitart.no2.common.util.Iterables.setOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A saved tunnel must come back with its paths. The converter stored every
 * path as null, and the next start died with an NPE in {@code Transport.init}
 * while loading them.
 */
class TunnelEntityStorageTest {

    @Test
    void tunnelPathsSurviveSaveAndLoad() {
        var mapper = new SimpleNitriteMapper();
        mapper.registerEntityConverter(new HopEntityConverter());
        mapper.registerEntityConverter(new TunnelEntityConverter());
        var db = Nitrite.builder()
                .loadModule(MVStoreModule.withConfig().build())
                .loadModule(() -> setOf(mapper))
                .openOrCreate();

        var expires = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        var hop = HopEntity.builder()
                .timestamp(expires)
                .via(new byte[]{1, 2})
                .hops(3)
                .expires(expires)
                .randomBlobs(List.of(new byte[]{4, 5}))
                .interfaceHash(new byte[]{6})
                .packetHash(new byte[]{7, 8})
                .build();
        db.getRepository(TunnelEntity.class).update(TunnelEntity.builder()
                .tunnelIdHex("aa")
                .tunnelId(new byte[]{(byte) 0xaa})
                .expires(expires)
                .tunnelPaths(Map.of("dest", hop))
                .build(), true);

        var loaded = db.getRepository(TunnelEntity.class).find().toList();
        assertEquals(1, loaded.size());
        var loadedHop = loaded.get(0).getTunnelPaths().get("dest");
        assertNotNull(loadedHop);
        assertEquals(3, loadedHop.getHops());
        assertArrayEquals(new byte[]{7, 8}, loadedHop.getPacketHash());
        assertArrayEquals(new byte[]{6}, loadedHop.getInterfaceHash());
        db.close();
    }
}
