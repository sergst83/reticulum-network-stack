package io.reticulum;

import io.reticulum.IdentityKnownDestination.DestinationData;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.reticulum.IdentityKnownDestination.KNOWN_DESTINATIONS;
import static io.reticulum.IdentityKnownDestination.KNOWN_DESTINATIONS_FILE_NAME;
import static org.apache.commons.lang3.SystemUtils.getJavaIoTmpDir;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityKnownDestinationTest {

    static Reticulum reticulumMock = mock(Reticulum.class);

    static Transport transport = Transport.start(reticulumMock);

    static Path configPath = getJavaIoTmpDir().toPath();

    @BeforeAll
    static void init() {
        when(reticulumMock.getStoragePath()).thenReturn(configPath);
    }

    @AfterAll
    static void destroy() throws IOException {
        Files.deleteIfExists(configPath.resolve(KNOWN_DESTINATIONS_FILE_NAME));
    }


    @Test
    void saveKnownDestinations() {
        var map = Map.of(
                Hex.encodeHexString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}),
                new DestinationData(System.currentTimeMillis(), new byte[]{1}, new byte[]{2}, new byte[]{3})
        );
        IdentityKnownDestination.KNOWN_DESTINATIONS.putAll(map);
        IdentityKnownDestination.saveKnownDestinations();

        assertTrue(Files.exists(configPath.resolve(KNOWN_DESTINATIONS_FILE_NAME)));
        assertTrue(Files.isRegularFile(configPath.resolve(KNOWN_DESTINATIONS_FILE_NAME)));
        assertTrue(Files.isReadable(configPath.resolve(KNOWN_DESTINATIONS_FILE_NAME)));
    }

    @Test
    void loadKnownDestinations() {
        var map = Map.of(
                Hex.encodeHexString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}),
                new DestinationData(System.currentTimeMillis(), new byte[]{1}, new byte[]{2}, new byte[]{3})
        );
        IdentityKnownDestination.KNOWN_DESTINATIONS.putAll(map);
        IdentityKnownDestination.saveKnownDestinations();

        KNOWN_DESTINATIONS.clear();
        assertTrue(KNOWN_DESTINATIONS.isEmpty());

        assertDoesNotThrow(IdentityKnownDestination::loadKnownDestinations);

        assertEquals(map, KNOWN_DESTINATIONS);
    }
}