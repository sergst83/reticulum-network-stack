package examples;

import io.reticulum.Reticulum;
import io.reticulum.Transport;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.identity.Identity;
import io.reticulum.transport.AnnounceHandler;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;

import static io.reticulum.destination.ProofStrategy.PROVE_ALL;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;

@Slf4j
public class ReticulumClient {
    static String IDENTITY_FILE_NAME = "identity";
    static String APP_NAME = "reticulum_client";
    static String CONFIG_PATH = "src/main/resources/reticulumclient";

    Reticulum reticulum;
    Identity appIdentity;
    Destination addDestination;
    public static void main(String[] args) {
        var reticulumClient = getInstance();
        reticulumClient.setup();
        reticulumClient.run();
    }

    static ReticulumClient getInstance () {
        return new ReticulumClient();
    }

    @SneakyThrows
    void setup() {
        this.reticulum = new Reticulum(CONFIG_PATH);
        this.appIdentity = getIdentity(reticulum);
        this.addDestination = createDestination(appIdentity);
    }

    void run() {
        var transport = Transport.getInstance();
        transport.registerAnnounceHandler(new AnnounceHandler() {
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

                if (appData != null) {
                    log.debug("The announce contained the following app data: {}", new String(appData));
                }
            }
        });
        newSingleThreadScheduledExecutor()
                        .scheduleWithFixedDelay(
                                () -> addDestination.announce(APP_NAME.getBytes()),
                                5,
                                60,
                                SECONDS
                        );
    }

    private Identity getIdentity(Reticulum reticulum) throws IOException {
        if (Objects.isNull(reticulum)) {
            return null;
        }
        var identityPath = reticulum.getResourcePath().resolve(IDENTITY_FILE_NAME);
        Identity destIdentity;
        if (Files.exists(identityPath)) {
            destIdentity = Identity.fromFile(identityPath);
        } else {
            destIdentity = new Identity();
            destIdentity.toFile(identityPath);
        }

        return destIdentity;
    }

    private Destination createDestination(Identity identity) {
        var destination = new Destination(
                identity,
                Direction.IN,
                DestinationType.SINGLE,
                APP_NAME
        );
        destination.setProofStrategy(PROVE_ALL);

        return destination;
    }
}
