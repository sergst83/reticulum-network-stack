package io.reticulum;

import io.reticulum.interfaces.AbstractConnectionInterface;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.vendor.config.ConfigObj;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.reticulum.utils.IdentityUtils.fullHash;
import static io.reticulum.utils.ReticulumConstant.CONFIG_FILE_NAME;
import static io.reticulum.utils.ReticulumConstant.ETC_DIR;
import static io.reticulum.utils.ReticulumConstant.IFAC_SALT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.SystemUtils.USER_HOME;

/**
 * This class is used to initialise access to Reticulum within a
 * program. You must create exactly one instance of this class before
 * carrying out any other RNS operations, such as creating destinations
 * or sending traffic. Every independently executed program must create
 * their own instance of the Reticulum class, but Reticulum will
 * automatically handle inter-program communication on the same system,
 * and expose all connected programs to external interfaces as well.
 * <p>
 * As soon as an instance of this class is created, Reticulum will start
 * opening and configuring any hardware devices specified in the supplied
 * configuration.
 * <p>
 * Currently the first running instance must be kept running while other
 * local instances are connected, as the first created instance will
 * act as a master instance that directly communicates with external
 * hardware such as modems, TNCs and radios. If a master instance is
 * asked to exit, it will not exit until all client processes have
 * terminated (unless killed forcibly).
 * <p>
 * If you are running Reticulum on a system with several different
 * programs that use RNS starting and terminating at different times,
 * it will be advantageous to run a master RNS instance as a daemon for
 * other programs to use on demand.
 */
@Slf4j
public class Reticulum implements ExitHandler, PersistData {
//    private final RNSInteface router;
    private final ConfigObj config;
//    private final Transport transport;
//    private final Identity identity;
    private String configDir;
    private Path configPath;
    private Path storagePath;
    private Path cachePath;
    private Path resourcePath;
    private Path identityPath;

    @Getter
    private boolean isConnectedToSharedInstance = false;
    private boolean isSharedInstance = false;
    private boolean isStandaloneInnstance = false;

    private boolean transportEnabled = false;
    private boolean useImplicitProof = true;
    private boolean panicOnIntefaceError = false;
    private int localIntefacePort = 37428;
    private int localControlPort = 37429;
    private boolean shareInstance = true;
    private Object rpcListener;
    private byte[] ifacSalt = IFAC_SALT;
    private Object jobsThread;
    private long lastDataPersist = System.currentTimeMillis();
    private long lastCacheClean = 0;

    /**
     * Initialises and starts a Reticulum instance. This must be
     * done before any other operations, and Reticulum will not
     * pass any traffic before being instantiated.
     *
     * @param configDir Full path to a Reticulum configuration directory.
     */
    public Reticulum(final String configDir) throws IOException {
        initConfig(configDir);

        if (Files.isRegularFile(configPath)) {
            try {
                this.config = ConfigObj.initConfig(configPath);
            } catch (Exception e) {
                log.error("Could not parse the configuration at {}. \nCheck your configuration file for errors!", configPath);
                throw e;
            }
        } else {
            log.info("Could not load config file, creating default configuration file...");
            createDefaultConfig();
            this.config = ConfigObj.initConfig(configPath);
            log.info("Default config file created. Make any necessary changes in {}/config and restart Reticulum if needed.", this.configDir);
        }

        var reticulumConfig = this.config.getReticulum();
        if (MapUtils.isNotEmpty(config.getInterfaces())) {
            for (ConnectionInterface connectionInterface : config.getInterfaces().values()) {
                var iface = (AbstractConnectionInterface) connectionInterface;
                if (StringUtils.isNotBlank(iface.getIfacNetName()) || StringUtils.isNotBlank(iface.getIfacNetKey())) {
                    var ifacOrigin = new byte[]{};
                    if (StringUtils.isNotBlank(iface.getIfacNetName())) {
                        ifacOrigin = ArrayUtils.addAll(ifacOrigin, fullHash(iface.getIfacNetName().getBytes(UTF_8)));
                    }
                    if (StringUtils.isNotBlank(iface.getIfacNetKey())) {
                        ifacOrigin = ArrayUtils.addAll(ifacOrigin, fullHash(iface.getIfacNetKey().getBytes(UTF_8)));
                    }
                    var ifacOriginHash = fullHash(ifacOrigin);
                    var hkdf = new HKDFBytesGenerator(new SHA256Digest());
                    hkdf.init(new HKDFParameters(ifacOriginHash, IFAC_SALT, new byte[0]));
                    var ifacKey = new byte[64];
                    hkdf.generateBytes(ifacKey, 0, ifacKey.length);

                    var identity = Identity.fromBytes(ifacKey);
                    iface.setIfacKey(ifacKey);
                    if (nonNull(identity)) {
                        iface.setIdentity(identity);
                        iface.setIfacSignature(identity.sign(fullHash(ifacKey)));
                    } else {
                        log.warn("Identity is null. Interface {} not initialiset correctly!", iface);
                    }
                }
                Transport.interfaces.add(connectionInterface);
            }
        }
    }

    /**
     * This exit handler is called whenever Reticulum is asked to
     * shut down, and will in turn call exit handlers in other
     * classes, saving necessary information to disk and carrying
     * out cleanup operations.
     */
    public void exitHandler() {
//        transport.exitHandler();
//        identity.exitHandler();
    }

    @Override
    public void persistData() {
//        transport.persistData();
//        identity.persistData();
    }

    private void initConfig(String configDir) throws IOException {
        if (StringUtils.isNotBlank(configDir)) {
            this.configDir = configDir;
        } else {
            if (Files.isDirectory(Path.of(ETC_DIR)) && Files.exists(Path.of(ETC_DIR, CONFIG_FILE_NAME))) {
                this.configDir = ETC_DIR;
            } else if (
                    Files.isDirectory(Path.of(USER_HOME, ".config", "reticulum"))
                            && Files.exists(Path.of(ETC_DIR, ".config", "reticulum", CONFIG_FILE_NAME))
            ) {
                this.configDir = Path.of(USER_HOME, ".config", "reticulum").toString();
            } else {
                this.configDir = Path.of(USER_HOME, ".reticulum").toString();
            }
        }

        this.configPath = Path.of(this.configDir, "config");
        this.storagePath = Path.of(this.configDir, "storage");
        this.cachePath = Path.of(this.configDir, "storage", "cache");
        this.resourcePath = Path.of(this.configDir, "storage", "resources");
        this.identityPath = Path.of(this.configDir, "storage", "identities");

        if (Files.notExists(storagePath)) {
            Files.createDirectories(storagePath);
        }

        if (Files.notExists(cachePath)) {
            Files.createDirectories(cachePath);
        }

        if (Files.notExists(resourcePath)) {
            Files.createDirectories(resourcePath);
        }

        if (Files.notExists(identityPath)) {
            Files.createDirectories(identityPath);
        }
    }

    private void createDefaultConfig() {
        try (var configIS = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE_NAME)) {
            if (nonNull(configIS)) {
                Files.copy(configIS, configPath);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
