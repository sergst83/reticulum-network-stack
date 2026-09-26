package examples;

import io.reticulum.Reticulum;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.identity.Identity;
import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import org.apache.commons.codec.binary.Hex;

import java.nio.file.Path;
import java.security.MessageDigest;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Step 5 responder: reports what it actually received, so the client can tell a
 * truncated frame from an intact one.
 * <p>
 * The echo is deliberately a digest rather than the payload: a reply carrying
 * the payload back would itself have to fit the MTU, and a truncation on the
 * return leg would then be indistinguishable from one on the way out.
 * <p>
 * Usage: {@code Step5FramingServer <config-dir>}
 */
public class Step5FramingServer {

    private static final String APP_NAME = "example_utilities";

    static String digest(byte[] data) throws Exception {
        return Hex.encodeHexString(MessageDigest.getInstance("SHA-256").digest(data));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: Step5FramingServer <config-dir>");
            System.exit(2);
        }

        new Reticulum(Path.of(args[0]).toAbsolutePath().toString());

        var identity = new Identity();
        var destination = new Destination(
                identity, Direction.IN, DestinationType.SINGLE, APP_NAME, "step5framing");
        destination.acceptsLinks(true);

        destination.setLinkEstablishedCallback(link -> {
            System.out.println("[step5-server] link established, mtu=" + link.getMtu() + " mdu=" + link.getMdu());
            System.out.flush();
            link.setPacketCallback((message, packet) -> {
                try {
                    var reply = "len=" + message.length + " sha256=" + digest(message);
                    System.out.println("[step5-server] received " + reply);
                    System.out.flush();
                    new Packet((Link) packet.getDestination(), reply.getBytes(UTF_8)).send();
                } catch (Exception e) {
                    System.out.println("[step5-server] echo failed: " + e);
                    System.out.flush();
                }
            });
        });

        System.out.println("[step5-server] destination <" + Hex.encodeHexString(destination.getHash()) + ">");
        System.out.println("[step5-server] ready, announcing");
        System.out.flush();

        while (true) {
            destination.announce();
            Thread.sleep(3000);
        }
    }
}
