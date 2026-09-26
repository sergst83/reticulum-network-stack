package examples;

import io.reticulum.Reticulum;
import io.reticulum.Transport;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;
import io.reticulum.resource.Resource;
import io.reticulum.resource.ResourceStatus;
import org.apache.commons.codec.binary.Hex;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.reticulum.identity.IdentityKnownDestination.recall;

/**
 * Step 3 of the live verification plan: does a resource actually transfer to a
 * Python RNS 1.5.2 peer, intact?
 * <p>
 * Resource transfer is the highest-risk area of the port — seven independent
 * bugs were found in it (BZIP2 truncation, {@code totalParts} left at zero,
 * advertisements timing out instantly, nil request-ID pack/unpack, the
 * advertisement metadata flag never being set, request/response size confusion,
 * and a segment count that multiplied where it should divide). None were
 * reachable by unit testing.
 * <p>
 * Sends to {@code Examples/Resource.py -s}, which accepts any resource and logs
 * the metadata, the byte count and the first 32 bytes. Those are compared
 * against what was sent, so the reference itself is the judge of correctness.
 * <p>
 * Usage: {@code Step3ResourceClient <config-dir> <dest-hash> <size-bytes> <compressible|random> <metadata|nometadata>}
 */
public class Step3ResourceClient {

    private static final String APP_NAME = "example_utilities";
    private static final long PATH_TIMEOUT_MS = 30_000;
    private static final long LINK_TIMEOUT_S = 30;
    private static final long TRANSFER_TIMEOUT_S = 300;

    private static void report(String stage, String detail) {
        System.out.println("[step3] " + stage + ": " + detail);
    }

    private static void fail(String stage, String detail) {
        System.out.println("[step3] FAIL at " + stage + ": " + detail);
        System.out.println("[step3] RESULT: FAIL");
        System.exit(1);
    }

    /**
     * Deterministic payload. "compressible" is highly repetitive so the BZIP2
     * path is taken; "random" is incompressible so it is not — the truncation
     * bug only showed on the compressed path.
     */
    private static byte[] payload(int size, boolean compressible) {
        var data = new byte[size];
        if (compressible) {
            for (int i = 0; i < size; i++) {
                data[i] = (byte) ('A' + (i % 8));
            }
        } else {
            new Random(20260906L).nextBytes(data);
        }

        return data;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: Step3ResourceClient <config-dir> <dest-hash> <size-bytes> "
                    + "<compressible|random> <metadata|nometadata>");
            System.exit(2);
        }

        var configDir = Path.of(args[0]).toAbsolutePath().toString();
        var destinationHash = Hex.decodeHex(args[1].trim());
        var size = Integer.parseInt(args[2]);
        var compressible = "compressible".equals(args[3]);
        var withMetadata = "metadata".equals(args[4]);

        report("start", size + " bytes, " + args[3] + ", " + args[4]);

        new Reticulum(configDir);
        var transport = Transport.getInstance();

        if (!transport.awaitPath(destinationHash, PATH_TIMEOUT_MS, null)) {
            fail("path", "no path to destination after " + PATH_TIMEOUT_MS + " ms");
        }
        var serverIdentity = recall(destinationHash);
        if (serverIdentity == null) {
            fail("identity", "could not recall server identity");
        }

        var serverDestination = new Destination(
                serverIdentity, Direction.OUT, DestinationType.SINGLE, APP_NAME, "resourceexample");

        var established = new CountDownLatch(1);
        var link = new Link(serverDestination);
        link.setLinkEstablishedCallback(l -> established.countDown());
        link.setLinkClosedCallback(l -> report("link", "closed, reason=" + l.getTeardownReason()));

        if (!established.await(LINK_TIMEOUT_S, TimeUnit.SECONDS)) {
            fail("link", "not established within " + LINK_TIMEOUT_S + "s (status=" + link.getStatus() + ")");
        }
        if (link.getStatus() != LinkStatus.ACTIVE) {
            fail("link", "not ACTIVE, status=" + link.getStatus());
        }
        report("link", "established, mdu=" + link.getMdu());

        var data = payload(size, compressible);
        var first32 = Hex.encodeHexString(java.util.Arrays.copyOf(data, Math.min(32, data.length)));

        // Emitted for the harness to compare against what the reference reports
        System.out.println("[step3] SENT_LENGTH " + data.length);
        System.out.println("[step3] SENT_FIRST32 " + first32);

        Object metadata = null;
        if (withMetadata) {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", "step3-probe");
            m.put("size", data.length);
            m.put("compressible", compressible);
            metadata = m;
            System.out.println("[step3] SENT_METADATA " + m);
        }

        var concluded = new CountDownLatch(1);
        var finalStatus = new AtomicReference<ResourceStatus>();

        report("send", "advertising resource");
        var resource = new Resource(
                data, link, metadata,
                r -> {
                    finalStatus.set(r.getStatus());
                    concluded.countDown();
                },
                r -> { },
                null, false, null, compressible, null, true);

        report("send", "segments=" + resource.getTotalSegments()
                + " parts=" + resource.getParts()
                + " compressed=" + resource.isCompressed()
                + " transferSize=" + resource.getSize()
                + " totalSize=" + resource.getTotalSize());

        if (resource.getParts() <= 0) {
            fail("advertise", "resource reports " + resource.getParts() + " parts before transfer");
        }

        if (!concluded.await(TRANSFER_TIMEOUT_S, TimeUnit.SECONDS)) {
            fail("transfer", "did not conclude within " + TRANSFER_TIMEOUT_S + "s (status="
                    + resource.getStatus() + ", progress=" + resource.getProgress() + ")");
        }
        if (finalStatus.get() != ResourceStatus.COMPLETE) {
            fail("transfer", "concluded with status " + finalStatus.get());
        }

        report("transfer", "COMPLETE");
        System.out.println("[step3] RESULT: SENT");

        Thread.sleep(1500);
        link.teardown();
        Thread.sleep(500);
        System.exit(0);
    }
}
