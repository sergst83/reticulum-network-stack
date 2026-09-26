package examples;

import io.reticulum.Reticulum;
import io.reticulum.Transport;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;
import io.reticulum.packet.Packet;
import org.apache.commons.codec.binary.Hex;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.reticulum.identity.IdentityKnownDestination.recall;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Step 1 of the live verification plan: does a link to a Python RNS 1.5.2 peer
 * actually carry encrypted traffic?
 * <p>
 * This is the decisive test for the AES-256 Token port. Link establishment alone
 * proves nothing — the handshake and proof signature validate even when the two
 * sides derive different key lengths, which is exactly why the AES-128/AES-256
 * mismatch stayed invisible. Only a successful round trip of encrypted data
 * shows the ciphers agree.
 * <p>
 * Run against {@code Examples/Link.py -s} from the reference, which echoes back
 * {@code I received "<text>" over the link}. That reply requires the server to
 * have decrypted our packet, and us to decrypt its reply — so a match exercises
 * link encryption in both directions.
 * <p>
 * Usage: {@code Step1InteropClient <config-dir> <destination-hash-hex>}
 * Exits 0 on success, 1 on failure.
 */
public class Step1InteropClient {

    private static final String APP_NAME = "example_utilities";
    private static final String PROBE_TEXT = "aes256 interop probe";
    private static final String EXPECTED_REPLY = "I received \"" + PROBE_TEXT + "\" over the link";

    private static final long PATH_TIMEOUT_MS = 30_000;
    private static final long LINK_TIMEOUT_S = 30;
    private static final long REPLY_TIMEOUT_S = 30;

    private static void report(String stage, String detail) {
        System.out.println("[step1] " + stage + ": " + detail);
    }

    private static void fail(String stage, String detail) {
        System.out.println("[step1] FAIL at " + stage + ": " + detail);
        System.out.println("[step1] RESULT: FAIL");
        System.exit(1);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: Step1InteropClient <config-dir> <destination-hash-hex>");
            System.exit(2);
        }

        var configDir = Path.of(args[0]).toAbsolutePath().toString();
        var destinationHash = Hex.decodeHex(args[1].trim());

        report("start", "config=" + configDir + " destination=" + args[1].trim());

        new Reticulum(configDir);
        var transport = Transport.getInstance();

        // 1. Resolve a path. Also exercises Transport.awaitPath, added this round.
        report("path", "awaiting path to destination");
        if (!transport.awaitPath(destinationHash, PATH_TIMEOUT_MS, null)) {
            fail("path", "no path to destination after " + PATH_TIMEOUT_MS + " ms. "
                    + "Is the Python link server announcing, and are both sides on the same interface?");
        }
        report("path", "path found, hops=" + transport.hopsTo(destinationHash));

        // 2. Recall the server identity from the announce
        var serverIdentity = recall(destinationHash);
        if (serverIdentity == null) {
            fail("identity", "could not recall server identity from the announce");
        }
        report("identity", "recalled server identity");

        // 3. Establish the link
        var serverDestination = new Destination(
                serverIdentity, Direction.OUT, DestinationType.SINGLE, APP_NAME, "linkexample");

        var established = new CountDownLatch(1);
        var reply = new AtomicReference<String>();
        var replyReceived = new CountDownLatch(1);

        var link = new Link(serverDestination);
        link.setLinkEstablishedCallback(l -> {
            report("link", "established, mode=" + l.getMode() + " rtt=" + l.getRtt() + "ms");
            established.countDown();
        });
        link.setLinkClosedCallback(l -> report("link", "closed, reason=" + l.getTeardownReason()));
        link.setPacketCallback((message, packet) -> {
            reply.set(new String(message, UTF_8));
            replyReceived.countDown();
        });

        if (!established.await(LINK_TIMEOUT_S, TimeUnit.SECONDS)) {
            fail("link", "link not established within " + LINK_TIMEOUT_S + "s (status=" + link.getStatus() + ")");
        }
        if (link.getStatus() != LinkStatus.ACTIVE) {
            fail("link", "link is not ACTIVE after establishment, status=" + link.getStatus());
        }

        // 4. Send encrypted data over the link. Everything above can succeed with
        //    mismatched ciphers; this is the part that cannot.
        report("send", "sending \"" + PROBE_TEXT + "\" over the link");
        new Packet(link, PROBE_TEXT.getBytes(UTF_8)).send();

        if (!replyReceived.await(REPLY_TIMEOUT_S, TimeUnit.SECONDS)) {
            fail("reply", "no reply within " + REPLY_TIMEOUT_S + "s. The link established but no decryptable "
                    + "data crossed it — this is the signature of a cipher mismatch. Check the Python log: "
                    + "if it shows a decryption error, the two sides disagree on key length.");
        }

        var received = reply.get();
        report("reply", "received \"" + received + "\"");

        if (!EXPECTED_REPLY.equals(received)) {
            fail("verify", "reply text did not match.\n  expected: " + EXPECTED_REPLY + "\n  actual:   " + received);
        }

        System.out.println();
        System.out.println("[step1] Link encryption verified in both directions against Python RNS 1.5.2.");
        System.out.println("[step1] RESULT: PASS");

        link.teardown();
        Thread.sleep(500);
        System.exit(0);
    }
}
