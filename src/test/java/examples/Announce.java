package examples;

import io.reticulum.Reticulum;
import io.reticulum.Transport;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.destination.ProofStrategy;
import io.reticulum.identity.Identity;
import io.reticulum.transport.AnnounceHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.nonNull;

@Slf4j
public class Announce {


    static final String APP_NAME = "announce_example";

    // Initialise two lists of strings to use as app_data
    static final List<String> FRUITS = List.of("Peach", "Quince", "Date", "Tangerine", "Pomelo", "Carambola", "Grape");
    static final List<String> NOBLE_GASES = List.of("Helium", "Neon", "Argon", "Krypton", "Xenon", "Radon", "Oganesson");

    @Test
    @Disabled("for manual use only")
    void announceTest() throws IOException {
        var configPath = Path.of("src/test/resources/announce").toAbsolutePath().toString();

        setup(configPath);
    }

    void setup(String configPath) throws IOException {
        var reticulum = new Reticulum(configPath);
        var identity = new Identity();

        var destination1 = new Destination(
                identity,
                Direction.IN,
                DestinationType.SINGLE,
                APP_NAME,
                "announcesample",
                "fruits"
        );

        var destination2 = new Destination(
                identity,
                Direction.IN,
                DestinationType.SINGLE,
                APP_NAME,
                "announcesample",
                "noble_gases"
        );

        destination1.setProofStrategy(ProofStrategy.PROVE_ALL);
        destination2.setProofStrategy(ProofStrategy.PROVE_ALL);

        Transport.getInstance().registerAnnounceHandler(new ExampleAnnounceHandler());

        announceLoop(destination1, destination2);
    }

    private void announceLoop(Destination destination1, Destination destination2) {
        var random = new Random();
        while (true) {
            if (Instant.now().toEpochMilli() % 10_000 == 0) {
                var fruit = FRUITS.get(random.nextInt(FRUITS.size()));
                destination1.announce(fruit.getBytes(UTF_8));

                log.debug("Sent announce from {} ({})", Hex.encodeHexString(destination1.getHash()), destination1.getName());

                var nobleGas = NOBLE_GASES.get(random.nextInt(NOBLE_GASES.size()));
                destination2.announce(nobleGas.getBytes(UTF_8));

                log.debug("Sent announce from {} ({})", Hex.encodeHexString(destination2.getHash()), destination2.getName());
            }
        }
    }

    @Slf4j
    private static class ExampleAnnounceHandler implements AnnounceHandler {

        @Override
        public String getAspectFilter() {
            return null;
        }

        @Override
        public void receivedAnnounce(
                byte[] destinationHash,
                Identity announcedIdentity,
                byte[] appData,
                byte[] announcePacketHash,
                boolean isPathResponse
        ) {
            log.debug("Received an announce from {}", Hex.encodeHexString(destinationHash));

            if (nonNull(appData)) {
                log.debug("The announce contained the following app data: {}", new String(appData, UTF_8));
            }
        }
    }
}
