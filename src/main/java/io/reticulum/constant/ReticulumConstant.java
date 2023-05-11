package io.reticulum.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static io.reticulum.constant.IdentityConstant.HASHLENGTH;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReticulumConstant {

    /**
     * Future minimum will probably be locked in at 251 bytes to support
     * networks with segments of different MTUs. Absolute minimum is 219.
     * <p>
     * The MTU that Reticulum adheres to, and will expect other peers to
     * adhere to. By default, the MTU is 507 bytes. In custom RNS network
     * implementations, it is possible to change this value, but doing so will
     * completely break compatibility with all other RNS networks. An identical
     * MTU is a prerequisite for peers to communicate in the same network.
     * <p>
     * Unless you really know what you are doing, the MTU should be left at
     * the default value.
     */
    public static final int MTU = 500;
    public static final int MAX_QUEUED_ANNOUNCES = 16384;
    public static final int QUEUED_ANNOUNCE_LIFE = 60 * 60 * 24;

    /**
     * The maximum percentage of interface bandwidth that, at any given time,
     * may be used to propagate announces. If an announce was scheduled for
     * broadcasting on an interface, but doing so would exceed the allowed
     * bandwidth allocation, the announce will be queued for transmission
     * when there is bandwidth available.
     * <p>
     * Reticulum will always prioritise propagating announces with fewer
     * hops, ensuring that distant, large networks with many peers on fast
     * links don't overwhelm the capacity of smaller networks on slower
     * mediums. If an announce remains queued for an extended amount of time,
     * it will eventually be dropped.
     * <p>
     * This value will be applied by default to all created interfaces,
     * but it can be configured individually on a per-interface basis.
     */
    public static final double ANNOUNCE_CAP = 2;
    public static final int MINIMUM_BITRATE = 500;

    // TODO: To reach the 300bps level without unreasonably impacting
    // performance on faster links, we need a mechanism for setting
    // this value more intelligently. One option could be inferring it
    // from interface speed, but a better general approach would most
    // probably be to let Reticulum somehow continously build a map of
    // per-hop latencies and use this map for the timeout calculation.
    public static final int DEFAULT_PER_HOP_TIMEOUT = 6_000;

    /**
     * Constant specifying the truncated hash length (in bits) used by Reticulum
     * for addressable hashes and other purposes. Non-configurable.
     */
    public static final int TRUNCATED_HASHLENGTH = 128;

    public static final int HEADER_MINSIZE = 2 + 1 + (TRUNCATED_HASHLENGTH / 8);
    public static final int HEADER_MAXSIZE = 2 + 1 + (TRUNCATED_HASHLENGTH / 8) * 2;
    public static final int IFAC_MIN_SIZE = 1;
    public static final byte[] IFAC_SALT;

    static {
        try {
            IFAC_SALT = Hex.decodeHex("adf54d882c9a9b80771eb4995d702d4a3e733391b2a0f53f416d9f907e55cff8");
        } catch (DecoderException e) {
            throw new RuntimeException(e);
        }
    }

    public static final int MDU = MTU - HEADER_MAXSIZE - IFAC_MIN_SIZE;
    public static final int RESOURCE_CACHE = 24 * 60 * 60;
    public static final int JOB_INTERVAL = 5 * 60;
    public static final int CLEAN_INTERVAL = 15 * 60;
    public static final int PERSIST_INTERVAL = 60 * 60 * 12;

    public static final String ETC_DIR = "/etc/reticulum";

    public static final String CONFIG_FILE_NAME = "config.yml";

    public static final BiConsumer<Stream<Path>, Integer> CLEAN_CONSUMER = (streamPath, ttlSec) ->
            streamPath
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toFile().getName().length() == HASHLENGTH / 8 * 2)
                    .filter(path -> System.currentTimeMillis() - path.toFile().lastModified() > Duration.ofSeconds(ttlSec).toMillis())
                    .forEach(path -> path.toFile().delete());
}
