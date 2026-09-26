package examples;

import io.reticulum.Reticulum;
import io.reticulum.Transport;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;
import io.reticulum.link.RequestReceipt;
import org.apache.commons.codec.binary.Hex;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.reticulum.identity.IdentityKnownDestination.recall;

/**
 * Step 4a: can a Java requester call a request handler on a Python RNS 1.5.2
 * peer and get the response back?
 * <p>
 * Counterpart is the reference's {@code Examples/Request.py -s}, which registers
 * a {@code /random/text} handler returning one of five known strings. Exercises
 * {@code Link.request()}, {@code RequestReceipt}, and the response path.
 * <p>
 * Usage: {@code Step4RequestClient <config-dir> <dest-hash>}
 */
public class Step4RequestClient {

    private static final String APP_NAME = "example_utilities";
    private static final long PATH_TIMEOUT_MS = 30_000;
    private static final long LINK_TIMEOUT_S = 30;
    private static final long RESPONSE_TIMEOUT_S = 60;

    /** The five strings Examples/Request.py can return. */
    private static final String[] EXPECTED = {
            "They looked up",
            "On each full moon",
            "Becky was upset",
            "I’ll stay away from it",
            "The pet shop stocks everything",
    };

    private static void report(String stage, String detail) {
        System.out.println("[step4a] " + stage + ": " + detail);
    }

    private static void fail(String stage, String detail) {
        System.out.println("[step4a] FAIL at " + stage + ": " + detail);
        System.out.println("[step4a] RESULT: FAIL");
        System.exit(1);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: Step4RequestClient <config-dir> <dest-hash>");
            System.exit(2);
        }

        var configDir = Path.of(args[0]).toAbsolutePath().toString();
        var destinationHash = Hex.decodeHex(args[1].trim());

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
                serverIdentity, Direction.OUT, DestinationType.SINGLE, APP_NAME, "requestexample");

        var established = new CountDownLatch(1);
        var link = new Link(serverDestination);
        link.setLinkEstablishedCallback(l -> established.countDown());

        if (!established.await(LINK_TIMEOUT_S, TimeUnit.SECONDS)) {
            fail("link", "not established (status=" + link.getStatus() + ")");
        }
        if (link.getStatus() != LinkStatus.ACTIVE) {
            fail("link", "not ACTIVE, status=" + link.getStatus());
        }
        report("link", "established, mdu=" + link.getMdu());

        var responded = new CountDownLatch(1);
        var response = new AtomicReference<String>();
        var failed = new AtomicReference<String>();

        report("request", "calling /random/text on the reference peer");
        RequestReceipt receipt = link.request(
                "/random/text",
                null,
                r -> {
                    var body = r.getResponse();
                    response.set(body == null ? null : new String(body, java.nio.charset.StandardCharsets.UTF_8));
                    responded.countDown();
                },
                r -> {
                    failed.set("status=" + r.getStatus());
                    responded.countDown();
                },
                null,
                null);

        if (receipt == null) {
            fail("request", "Link.request returned null — the request was not sent");
        }

        if (!responded.await(RESPONSE_TIMEOUT_S, TimeUnit.SECONDS)) {
            fail("response", "no response within " + RESPONSE_TIMEOUT_S + "s");
        }
        if (failed.get() != null) {
            fail("response", "request failed: " + failed.get());
        }

        var body = response.get();
        report("response", "received \"" + body + "\"");

        var known = false;
        for (var candidate : EXPECTED) {
            if (candidate.equals(body)) {
                known = true;
                break;
            }
        }
        if (!known) {
            fail("verify", "response is not one of the reference's known strings: \"" + body + "\"");
        }

        System.out.println("[step4a] RESULT: PASS");
        link.teardown();
        Thread.sleep(500);
        System.exit(0);
    }
}
