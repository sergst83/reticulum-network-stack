package examples;

import io.reticulum.Reticulum;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.destination.Request;
import io.reticulum.destination.RequestPolicy;
import io.reticulum.destination.Response;
import io.reticulum.identity.Identity;
import org.apache.commons.codec.binary.Hex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.isNull;

/**
 * Step 7: a minimal rngit-protocol responder, so the <em>stock</em>
 * {@code git-remote-rns} from Python RNS can clone from and push to a Java
 * node.
 * <p>
 * It is not an rngit server: no permissions, groups, releases or work
 * documents, and one bare repository served under any {@code group/repo} path.
 * It implements just enough of {@code RNS/Utilities/rngit/server.py}
 * ({@code handle_list}, {@code handle_fetch}, {@code handle_push}) to exercise
 * what this library must get right for rngit:
 * <ul>
 *   <li>map-shaped request data with integer and string keys</li>
 *   <li>a multi-segment file response carrying {@code {1: 0}} metadata</li>
 *   <li>a large request (the push bundle) arriving as a resource</li>
 *   <li>remote identification over the link</li>
 * </ul>
 * Usage: {@code Step7GitServer <config-dir> <bare-repo>}
 */
public class Step7GitServer {

    private static final String APP_NAME = "git";

    private static final long IDX_REPOSITORY = 0x00;
    private static final int IDX_RESULT_CODE = 0x01;

    private static final byte RES_OK = 0x00;
    private static final byte RES_DISALLOWED = 0x01;
    private static final byte RES_INVALID_REQ = 0x02;
    private static final byte RES_REMOTE_FAIL = (byte) 0xFF;

    private static Path repo;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: Step7GitServer <config-dir> <bare-repo>");
            System.exit(2);
        }

        var configDir = Path.of(args[0]).toAbsolutePath().toString();
        repo = Path.of(args[1]).toAbsolutePath();

        new Reticulum(configDir);

        var identity = new Identity();
        var destination = new Destination(identity, Direction.IN, DestinationType.SINGLE, APP_NAME, "repositories");
        destination.acceptsLinks(true);

        destination.registerRequestHandler("/git/list", Step7GitServer::handleList, RequestPolicy.ALLOW_ALL, null, true);
        destination.registerRequestHandler("/git/fetch", Step7GitServer::handleFetch, RequestPolicy.ALLOW_ALL, null, false);
        destination.registerRequestHandler("/git/push", Step7GitServer::handlePush, RequestPolicy.ALLOW_ALL, null, true);
        // Sleeps before answering: a request sent after it on the same link must not wait for it
        destination.registerRequestHandler("/slow", request -> {
            try {
                Thread.sleep(8000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Response.of("slow".getBytes(UTF_8));
        }, RequestPolicy.ALLOW_ALL, null, false);

        System.out.println("[step7] destination <" + Hex.encodeHexString(destination.getHash()) + ">");
        System.out.println("[step7] serving " + repo);
        System.out.flush();

        while (true) {
            destination.announce();
            Thread.sleep(3000);
        }
    }

    private static Response result(byte code, String message) {
        var text = message.getBytes(UTF_8);
        var out = new byte[1 + text.length];
        out[0] = code;
        System.arraycopy(text, 0, out, 1, text.length);

        return Response.of(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> requestMap(Request request) {
        var data = request.getDataObject();

        return data instanceof Map ? (Map<Object, Object>) data : null;
    }

    private static String identityOf(Request request) {
        return isNull(request.getRemoteIdentity()) ? "none" : Hex.encodeHexString(request.getRemoteIdentity().getHash());
    }

    private static Response handleList(Request request) {
        var data = requestMap(request);
        if (isNull(request.getRemoteIdentity())) return result(RES_DISALLOWED, "Not identified");
        if (isNull(data) || !data.containsKey(IDX_REPOSITORY)) return result(RES_INVALID_REQ, "No repository specified");
        System.out.println("[step7] list " + data.get(IDX_REPOSITORY) + " for_push=" + data.get("for_push")
                + " from " + identityOf(request));

        try {
            var head = Files.readString(repo.resolve("HEAD"), UTF_8).trim();
            var headRef = head.startsWith("ref: ") ? head.substring(5).trim() : "master";
            var refs = git(List.of("for-each-ref", "--format", "%(objectname) %(refname)")).trim();
            var output = (refs.isEmpty() ? "" : refs + "\n") + "@" + headRef + " HEAD\n";

            return result(RES_OK, output);
        } catch (Exception e) {
            System.out.println("[step7] list failed: " + e);
            return result(RES_REMOTE_FAIL, "Could not list refs");
        }
    }

    @SuppressWarnings("unchecked")
    private static Response handleFetch(Request request) {
        var data = requestMap(request);
        if (isNull(request.getRemoteIdentity())) return result(RES_DISALLOWED, "Not identified");
        if (isNull(data) || !(data.get("refs") instanceof List)) return result(RES_INVALID_REQ, "No refs specified");

        try {
            var bundle = Files.createTempFile("step7-fetch-", ".bundle");
            bundle.toFile().deleteOnExit();
            Files.delete(bundle);

            var cmd = new ArrayList<>(List.of("bundle", "create", "--no-progress", bundle.toString()));
            for (var entry : (List<Object>) data.get("refs")) {
                var ref = (Map<Object, Object>) entry;
                cmd.add((String) ref.get("ref"));
                if (ref.get("have") instanceof String) cmd.add("^" + ref.get("have"));
            }
            if (data.get("have") instanceof List) {
                for (var sha : (List<Object>) data.get("have")) cmd.add("^" + sha);
            }

            var proc = run(cmd);
            if (proc.code != 0) {
                if (proc.stderr.toLowerCase().contains("empty bundle")) return Response.of(new byte[]{RES_OK});
                System.out.println("[step7] fetch failed: " + proc.stderr);
                return result(RES_REMOTE_FAIL, "Could not fetch refs");
            }

            System.out.println("[step7] fetch " + data.get(IDX_REPOSITORY) + " refs=" + ((List<Object>) data.get("refs")).size()
                    + " bundle=" + Files.size(bundle) + "B");
            return Response.ofFile(bundle.toFile(), Map.of(IDX_RESULT_CODE, (int) RES_OK));
        } catch (Exception e) {
            System.out.println("[step7] fetch failed: " + e);
            return result(RES_REMOTE_FAIL, "Remote error");
        }
    }

    private static Response handlePush(Request request) {
        var data = requestMap(request);
        if (isNull(request.getRemoteIdentity())) return result(RES_DISALLOWED, "Not identified");
        if (isNull(data)) return result(RES_INVALID_REQ, "Invalid request");

        var localRef = data.get("local_ref");
        var remoteRef = data.get("remote_ref");
        var bundleData = data.get("bundle");
        if (!(bundleData instanceof byte[]) || !(localRef instanceof String) || !(remoteRef instanceof String)) {
            return result(RES_INVALID_REQ, "Only bundle pushes are implemented by this test server");
        }

        try {
            var bundle = Files.createTempFile("step7-push-", ".bundle");
            Files.write(bundle, (byte[]) bundleData);
            try {
                if (run(List.of("bundle", "verify", bundle.toString())).code != 0) {
                    return result(RES_REMOTE_FAIL, "Could not verify bundle");
                }
                var cmd = new ArrayList<>(List.of("fetch", bundle.toString(), localRef + ":" + remoteRef));
                if (Boolean.TRUE.equals(data.get("force"))) cmd.add("--force");
                var proc = run(cmd);
                if (proc.code != 0) {
                    System.out.println("[step7] push failed: " + proc.stderr);
                    return result(RES_REMOTE_FAIL, "Could not apply bundle");
                }
            } finally {
                Files.deleteIfExists(bundle);
            }

            System.out.println("[step7] push " + localRef + ":" + remoteRef + " bundle=" + ((byte[]) bundleData).length
                    + "B from " + identityOf(request));
            return Response.of(new byte[]{RES_OK});
        } catch (Exception e) {
            System.out.println("[step7] push failed: " + e);
            return result(RES_REMOTE_FAIL, "Remote error");
        }
    }

    private static final class Proc {
        int code;
        String stdout;
        String stderr;
    }

    private static String git(List<String> args) throws IOException, InterruptedException {
        var proc = run(args);
        if (proc.code != 0) throw new IOException("git " + args + ": " + proc.stderr);

        return proc.stdout;
    }

    private static Proc run(List<String> args) throws IOException, InterruptedException {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.addAll(args);
        var process = new ProcessBuilder(cmd).directory(repo.toFile()).start();

        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var errReader = new Thread(() -> {
            try {
                process.getErrorStream().transferTo(err);
            } catch (IOException ignored) {
            }
        });
        errReader.start();
        process.getInputStream().transferTo(out);
        errReader.join();

        var proc = new Proc();
        proc.code = process.waitFor();
        proc.stdout = out.toString(UTF_8);
        proc.stderr = err.toString(UTF_8);

        return proc;
    }
}
