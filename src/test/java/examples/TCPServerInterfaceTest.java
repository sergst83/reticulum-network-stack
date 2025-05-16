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
import java.time.Duration;

@Slf4j
public class TCPServerInterfaceTest {
    private static final String APP_NAME = "java_example";

//    static {
//        System.setProperty("log4j.configurationFile", "src/main/resources/log4j2.xml");
//    }

    @Test
    @Disabled("run manually")
    void start() throws IOException, InterruptedException {
        var configPath = Path.of("src/main/resources/example").toAbsolutePath().toString();
        var reticulum = new Reticulum(configPath);
        var identity = new Identity();
        var destination = new Destination(
                identity,
                Direction.IN,
                DestinationType.SINGLE,
                APP_NAME
        );
        destination.setPacketCallback((message, packet) -> {
            log.debug("Message raw {}", message);
            log.debug("Packet {}", packet.toString());
        });
        destination.setProofStrategy(ProofStrategy.PROVE_ALL);
        destination.announce();

        var program = new ExampleApp(Transport.getInstance());
        program.destination = destination;
        log.debug(destination.getHexHash());
        program.announceLoop();
    }

    static class ExampleApp {
        Transport transport;
        Destination destination;

        ExampleApp(Transport transport) {
            this.transport = transport;
            this.transport.registerAnnounceHandler(new AnnounceHandler() {
                @Override
                public String getAspectFilter() {
                    return null;
                }

                @Override
                public void receivedAnnounce(byte[] destinationHash, Identity announcedIdentity, byte[] appData) {
                    log.debug("Received an announce from {}", Hex.encodeHexString(destinationHash));

                    if (appData != null) {
                        log.debug("The announce contained the following app data: {}", new String(appData));
                    }
                }
            });
        }

        private void announceLoop() throws InterruptedException {
            while (true) {
//                destination.announce("TestServer".getBytes(UTF_8));
                log.debug("Sent announce for {}", destination.getHexHash());

                Thread.sleep(Duration.ofSeconds(15).toMillis());
            }
        }
    }
}
