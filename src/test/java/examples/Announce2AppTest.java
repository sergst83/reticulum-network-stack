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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofSeconds;

@Slf4j
public class Announce2AppTest {
    private static final String APP_NAME = "announce_example";

    // Initialise two lists of strings to use as app_data
    static final List<String> FRUITS = List.of("Peach", "Quince", "Date", "Tangerine", "Pomelo", "Carambola", "Grape");
    static final List<String> NOBLE_GASES = List.of("Helium", "Neon", "Argon", "Krypton", "Xenon", "Radon", "Oganesson");

    Reticulum reticulum;
    Identity identity;
    Transport transport;
    Destination destination1, destination2;
    //private static Logger log = LogManager.getLogger(AnnounceApp.class);
    static final  String defaultConfigPath = "src/main/resources/example"; // if empty will look in Reticulums default paths

    @BeforeEach
    void setup() {
        try {
            reticulum = new Reticulum(defaultConfigPath);
            transport = Transport.getInstance();
        } catch (IOException e) {
            log.error("unable to create Reticulum network", e);
        }
        identity = new Identity();

        destination1 = new Destination(
                identity,
                Direction.IN,
                DestinationType.SINGLE,
                APP_NAME,
                "announcesample",
                "fruits"
        );
        destination2 = new Destination(
                identity,
                Direction.IN,
                DestinationType.SINGLE,
                APP_NAME,
                "announcesample",
                "noble_gases"
        );
        log.info("destination1 hash: " + destination1.getHexHash());
        log.info("destination2 hash: " + destination2.getHexHash());

        destination1.setProofStrategy(ProofStrategy.PROVE_ALL);
        destination2.setProofStrategy(ProofStrategy.PROVE_ALL);

        // create a custom announce handler instance
        var announceHandler = new Announce2AppTest.ExampleAnnounceHandler();

        // register announce handler
        transport.registerAnnounceHandler(announceHandler);
        log.debug("announce handlers: {}", transport.getAnnounceHandlers());
    }

    @Test
    @Disabled("manual use only")
    void mainTest() throws InterruptedException {
        announceLoop(destination2, destination1);
    }

    public void announceLoop(Destination d1, Destination d2) throws InterruptedException {
        Thread.sleep(ofSeconds(15).toMillis());
        while (true) {
            var random = new Random();
            var fruit = FRUITS.get(random.nextInt(FRUITS.size()));
            d1.announce(fruit.getBytes(UTF_8));

            log.debug("Sent announce from {} ({})", Hex.encodeHexString(d1.getHash()), d1.getName());

            var nobleGas = NOBLE_GASES.get(random.nextInt(NOBLE_GASES.size()));
            d2.announce(nobleGas.getBytes(UTF_8));

            log.debug("Sent announce from {} ({})", Hex.encodeHexString(d2.getHash()), d2.getName());

            Thread.sleep(ofSeconds(15).toMillis());
        }
    }

    private class ExampleAnnounceHandler implements AnnounceHandler {
        @Override
        public String getAspectFilter() {
            log.info("getAspectFilter called.");
            //return APP_NAME;
            return null;
        }

        @Override
        public void receivedAnnounce(byte[] destinationHash, Identity announcedIdentity, byte[] appData) {
            log.info("Received an announce from {}", Hex.encodeHexString(destinationHash));

            if (appData != null) {
                log.info("The announce contained the following app data: {}", new String(appData));
            }
        }
    }
}
