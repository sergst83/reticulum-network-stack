package examples;

import io.reticulum.Reticulum;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.destination.RequestPolicy;
import io.reticulum.destination.Response;
import io.reticulum.identity.Identity;
import org.apache.commons.codec.binary.Hex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Step 4b: a Java request responder for a Python RNS 1.5.2 requester.
 * <p>
 * This is the side that exercises the work added this round — the metadata
 * channel lives on the responder, since only a file response carries metadata
 * ({@code RNS/Link.py:836-851}). Three handlers cover the shapes that matter:
 *
 * <ul>
 *   <li>{@code /small} — a byte response that fits in a single packet</li>
 *   <li>{@code /large} — a byte response past the link MDU, so it goes as a
 *       resource</li>
 *   <li>{@code /file} — a file response carrying metadata, the rngit-shaped
 *       case</li>
 * </ul>
 *
 * Runs until killed, printing its destination hash for the caller to use.
 * <p>
 * Usage: {@code Step4RequestServer <config-dir> <response-bytes>}
 */
public class Step4RequestServer {

    private static final String APP_NAME = "example_utilities";

    static final String SMALL_BODY = "java-small-response";

    /** Deterministic so the Python side can verify content, not just length. */
    static byte[] largeBody(int size) {
        var data = new byte[size];
        new Random(20260906L).nextBytes(data);

        return data;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: Step4RequestServer <config-dir> <response-bytes>");
            System.exit(2);
        }

        var configDir = Path.of(args[0]).toAbsolutePath().toString();
        var largeSize = Integer.parseInt(args[1]);

        new Reticulum(configDir);

        var identity = new Identity();
        var destination = new Destination(
                identity, Direction.IN, DestinationType.SINGLE, APP_NAME, "requestexample");
        destination.acceptsLinks(true);

        // A file response, prepared once. Its metadata is what the requester
        // should receive alongside the bytes.
        var file = Files.createTempFile("step4-file-response-", ".bin");
        var fileBody = largeBody(largeSize);
        Files.write(file, fileBody);
        file.toFile().deleteOnExit();

        var fileMetadata = new LinkedHashMap<String, Object>();
        fileMetadata.put("name", "step4-file");
        fileMetadata.put("size", fileBody.length);
        fileMetadata.put("code", 0);

        destination.registerRequestHandler(
                "/small",
                request -> Response.of(SMALL_BODY.getBytes(UTF_8)),
                RequestPolicy.ALLOW_ALL,
                null,
                true);

        destination.registerRequestHandler(
                "/large",
                request -> Response.of(largeBody(largeSize)),
                RequestPolicy.ALLOW_ALL,
                null,
                false);

        destination.registerRequestHandler(
                "/file",
                request -> Response.ofFile(file.toFile(), fileMetadata),
                RequestPolicy.ALLOW_ALL,
                null,
                false);

        System.out.println("[step4b] destination <" + Hex.encodeHexString(destination.getHash()) + ">");
        System.out.println("[step4b] small=" + SMALL_BODY.length() + "B large=" + largeSize
                + "B file=" + fileBody.length + "B");
        System.out.println("[step4b] first32 " + Hex.encodeHexString(java.util.Arrays.copyOf(fileBody, 32)));
        System.out.println("[step4b] ready, announcing");
        System.out.flush();

        // Announce periodically so the Python requester can find us
        while (true) {
            destination.announce();
            Thread.sleep(3000);
        }
    }
}
