package examples;

import io.reticulum.Reticulum;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.identity.Identity;
import org.apache.commons.codec.binary.Hex;

import java.nio.file.Path;

/**
 * A destination built from a fixed identity, so its hash is known before the
 * node hosting it exists.
 * <p>
 * That is what makes the path-request batching test deterministic: peers can be
 * pointed at a destination nothing on the network hosts yet, so their requests
 * genuinely go unanswered and pile up on the transport node for as long as the
 * test needs. Waiting for a live destination to stay quiet would not work — a
 * node answers a path request for a destination it hosts regardless of its
 * announce schedule, closing the window on loopback within milliseconds.
 * <p>
 * With {@code silence} negative the hash is printed and the process exits, which
 * is how the harness learns the hash up front.
 * <p>
 * Usage: {@code Step7DelayedDestination <config-dir> <silence-seconds> <identity-hex>}
 */
public class Step7DelayedDestination {

    private static final String APP_NAME = "example_utilities";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: Step7DelayedDestination <config-dir> <silence-seconds> <identity-hex>");
            System.exit(2);
        }

        var silence = Integer.parseInt(args[1]);
        var identity = Identity.fromBytes(Hex.decodeHex(args[2].trim()));

        // Announcing-only mode still needs a stack; hash-only mode does not, but
        // building the destination is what derives the hash, so do it either way.
        new Reticulum(Path.of(args[0]).toAbsolutePath().toString());

        var destination = new Destination(
                identity, Direction.IN, DestinationType.SINGLE, APP_NAME, "step7path");
        destination.acceptsLinks(true);

        System.out.println("[step7-dest] destination <" + Hex.encodeHexString(destination.getHash()) + ">");
        System.out.flush();

        if (silence < 0) {
            System.out.println("[step7-dest] hash only, exiting");
            System.out.flush();
            System.exit(0);
        }

        if (silence > 0) {
            System.out.println("[step7-dest] staying silent for " + silence + "s");
            System.out.flush();
            Thread.sleep(silence * 1000L);
        }

        System.out.println("[step7-dest] announcing now");
        System.out.flush();
        while (true) {
            destination.announce();
            Thread.sleep(3000);
        }
    }
}
