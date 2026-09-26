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
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.reticulum.identity.IdentityKnownDestination.recall;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Step 5 of the live verification plan: does a frame at the negotiated MTU
 * actually cross intact, and is the MTU negotiated to something the interface
 * can carry?
 * <p>
 * Java↔Java is the case that matters here. Against the reference, the responder
 * clamps the advertised MTU for us ({@code RNS/Transport.py:2544}), so a missing
 * clamp on our side stays invisible; between two Java nodes nothing does. This
 * asserts the negotiated MTU against an expected value — supplied by the
 * harness, which knows each side's configured bitrate — and then pushes a packet
 * of exactly the link MDU through and has the peer report back the length and
 * digest it decoded.
 * <p>
 * Usage: {@code Step5FramingClient <config-dir> <dest-hash> <expected-mtu>}
 * Exits 0 on success, 1 on failure.
 */
public class Step5FramingClient {

    private static final String APP_NAME = "example_utilities";
    private static final long PATH_TIMEOUT_MS = 30_000;
    private static final long LINK_TIMEOUT_S = 30;
    private static final long REPLY_TIMEOUT_S = 60;

    private static void report(String stage, String detail) {
        System.out.println("[step5] " + stage + ": " + detail);
        System.out.flush();
    }

    private static void fail(String stage, String detail) {
        System.out.println("[step5] FAIL at " + stage + ": " + detail);
        System.out.println("[step5] RESULT: FAIL");
        System.exit(1);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: Step5FramingClient <config-dir> <dest-hash> <expected-mtu>");
            System.exit(2);
        }

        var configDir = Path.of(args[0]).toAbsolutePath().toString();
        var destinationHash = Hex.decodeHex(args[1].trim());
        var expectedMtu = Integer.parseInt(args[2]);

        new Reticulum(configDir);
        var transport = Transport.getInstance();

        if (!transport.awaitPath(destinationHash, PATH_TIMEOUT_MS, null)) {
            fail("path", "no path to destination");
        }
        var serverIdentity = recall(destinationHash);
        if (serverIdentity == null) {
            fail("identity", "could not recall server identity");
        }

        var serverDestination = new Destination(
                serverIdentity, Direction.OUT, DestinationType.SINGLE, APP_NAME, "step5framing");

        var established = new CountDownLatch(1);
        var replyReceived = new CountDownLatch(1);
        var reply = new AtomicReference<String>();

        var link = new Link(serverDestination);
        link.setLinkEstablishedCallback(l -> established.countDown());
        link.setPacketCallback((message, packet) -> {
            reply.set(new String(message, UTF_8));
            replyReceived.countDown();
        });

        if (!established.await(LINK_TIMEOUT_S, TimeUnit.SECONDS)) {
            fail("link", "not established (status=" + link.getStatus() + ")");
        }
        if (link.getStatus() != LinkStatus.ACTIVE) {
            fail("link", "not ACTIVE, status=" + link.getStatus());
        }

        var mtu = link.getMtu();
        var mdu = link.getMdu();
        report("link", "established, mtu=" + mtu + " mdu=" + mdu);

        if (mtu != expectedMtu) {
            fail("negotiate", "negotiated MTU " + mtu + ", expected " + expectedMtu
                    + ". A too-high value means the responder confirmed more than its interface"
                    + " declared — the clamp in Transport did not engage.");
        }

        // A packet of exactly the MDU: the largest single frame this link claims
        // to support. Random content so a truncation cannot accidentally match.
        var payload = new byte[mdu];
        new Random(20260906L).nextBytes(payload);
        var expectedDigest = Hex.encodeHexString(MessageDigest.getInstance("SHA-256").digest(payload));

        report("send", "sending a packet of exactly the MDU (" + mdu + " bytes)");
        new Packet(link, payload).send();

        if (!replyReceived.await(REPLY_TIMEOUT_S, TimeUnit.SECONDS)) {
            fail("reply", "no reply within " + REPLY_TIMEOUT_S + "s — the frame did not arrive."
                    + " At exactly the MDU this is what a deframer or receive-buffer limit below the"
                    + " negotiated MTU looks like.");
        }

        var expected = "len=" + payload.length + " sha256=" + expectedDigest;
        var received = reply.get();
        report("reply", received);

        if (!expected.equals(received)) {
            fail("verify", "the peer did not decode what was sent.\n  expected: " + expected
                    + "\n  actual:   " + received);
        }

        report("verify", "a full-MDU frame crossed intact");
        System.out.println("[step5] RESULT: PASS");

        link.teardown();
        Thread.sleep(500);
        System.exit(0);
    }
}
