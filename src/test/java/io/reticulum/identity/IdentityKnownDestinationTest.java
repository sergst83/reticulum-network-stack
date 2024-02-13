package io.reticulum.identity;

import io.reticulum.Reticulum;
import io.reticulum.Transport;
import io.reticulum.identity.IdentityKnownDestination.DestinationData;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.reticulum.identity.IdentityKnownDestination.KNOWN_DESTINATIONS;
import static org.apache.commons.lang3.SystemUtils.getJavaIoTmpDir;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdentityKnownDestinationTest {

    @Mock
    Reticulum reticulumMock;

    Transport transport;

    Path configPath = getJavaIoTmpDir().toPath();

    @BeforeEach
    void init() {
        when(reticulumMock.getStoragePath()).thenReturn(configPath);
        transport = Transport.start(reticulumMock);
    }

    @AfterEach
    void destroy() throws IOException {
        Files.deleteIfExists(configPath.resolve("jreticulum.db"));
    }


    @Test
    void saveKnownDestinations() {
        var key = Hex.encodeHexString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        var map = Map.of(
                key,
                new DestinationData(key, System.currentTimeMillis(), new byte[]{1}, new byte[]{2}, new byte[]{3})
        );
        IdentityKnownDestination.KNOWN_DESTINATIONS.putAll(map);
        IdentityKnownDestination.saveKnownDestinations();

        KNOWN_DESTINATIONS.clear();
        assertTrue(KNOWN_DESTINATIONS.isEmpty());

        IdentityKnownDestination.loadKnownDestinations();

        assertEquals(map, KNOWN_DESTINATIONS);
    }

    @Test
    void loadKnownDestinations() {
        var key = Hex.encodeHexString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        var map = Map.of(
                key,
                new DestinationData(key, System.currentTimeMillis(), new byte[]{1}, new byte[]{2}, new byte[]{3})
        );
        IdentityKnownDestination.KNOWN_DESTINATIONS.putAll(map);
        IdentityKnownDestination.saveKnownDestinations();

        KNOWN_DESTINATIONS.clear();
        assertTrue(KNOWN_DESTINATIONS.isEmpty());

        assertDoesNotThrow(IdentityKnownDestination::loadKnownDestinations);

        assertEquals(map, KNOWN_DESTINATIONS);
    }
}