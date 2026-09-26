package examples;

import io.reticulum.Reticulum;
import io.reticulum.Transport;
import org.apache.commons.codec.binary.Hex;

import java.nio.file.Path;

/**
 * A peer that does nothing but resolve a path, and reports how long it took.
 * <p>
 * Two of these, started together against a destination nothing knows yet, are
 * what proves path requests are batched: both must be answered from the single
 * recursive request the transport node issues. Before
 * {@code PathRequestEntry.requestingInterfaces} became a list, the transport
 * node recorded only the first requester, and the second had to wait out its own
 * retry.
 * <p>
 * Usage: {@code Step7PathRequester <config-dir> <dest-hash> <timeout-ms> <label>}
 * Exits 0 if the path resolved within the timeout.
 */
public class Step7PathRequester {

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: Step7PathRequester <config-dir> <dest-hash> <timeout-ms> <label>");
            System.exit(2);
        }

        var configDir = Path.of(args[0]).toAbsolutePath().toString();
        var destinationHash = Hex.decodeHex(args[1].trim());
        var timeoutMs = Long.parseLong(args[2]);
        var label = args[3];

        new Reticulum(configDir);
        var transport = Transport.getInstance();

        // await_path sends exactly one request and never retries — the reference
        // behaves the same way (RNS/Transport.py await_path). Asking before the
        // interface has finished connecting therefore loses the request outright,
        // so wait for a live interface first.
        for (var i = 0; i < 100; i++) {
            var online = transport.getInterfaces().stream().anyMatch(iface -> iface.isOnline());
            if (online) {
                break;
            }
            Thread.sleep(100);
        }
        Thread.sleep(500);

        var started = System.currentTimeMillis();
        var found = transport.awaitPath(destinationHash, timeoutMs, null);
        var elapsed = System.currentTimeMillis() - started;

        if (found) {
            System.out.println("[step7-" + label + "] path resolved in " + elapsed + "ms");
            System.out.println("[step7-" + label + "] RESULT: PASS");
            System.exit(0);
        }

        System.out.println("[step7-" + label + "] no path after " + elapsed + "ms");
        System.out.println("[step7-" + label + "] RESULT: FAIL");
        System.exit(1);
    }
}
