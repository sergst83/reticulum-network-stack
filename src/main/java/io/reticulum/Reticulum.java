package io.reticulum;

import io.reticulum.config.ConfigObj;
import io.reticulum.interfaces.AbstractConnectionInterface;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.interfaces.local.LocalClientInterface;
import io.reticulum.interfaces.local.LocalServerInterface;
import io.reticulum.storage.Storage;
import io.reticulum.utils.IdentityUtils;
import io.reticulum.utils.InterfaceUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import sun.misc.Signal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static io.reticulum.constant.ReticulumConstant.CLEAN_CONSUMER;
import static io.reticulum.constant.ReticulumConstant.CLEAN_INTERVAL;
import static io.reticulum.constant.ReticulumConstant.CONFIG_FILE_NAME;
import static io.reticulum.constant.ReticulumConstant.ETC_DIR;
import static io.reticulum.constant.ReticulumConstant.IFAC_SALT;
import static io.reticulum.constant.ReticulumConstant.PERSIST_INTERVAL;
import static io.reticulum.constant.ReticulumConstant.RESOURCE_CACHE;
import static io.reticulum.identity.IdentityKnownDestination.loadKnownDestinations;
import static io.reticulum.utils.CommonUtils.exit;
import static io.reticulum.utils.CommonUtils.panic;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Objects.nonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
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
public class Reticulum implements ExitHandler {
    private final Transport transport;

    private ConfigObj config;
    private Path configPath;
    @Getter
    private Path storagePath;
    @Getter
    private Path resourcePath;

    @Getter
    private boolean isConnectedToSharedInstance = false;
    private boolean isSharedInstance = false;
    private boolean isStandaloneInnstance = false;

    @Getter
    private boolean transportEnabled = false;
    @Getter
    private boolean useImplicitProof = true;
    @Getter
    private boolean allowProbes = false;
    @Getter
    private boolean panicOnIntefaceError = false;
    private int localIntefacePort = 37428;
    private boolean shareInstance = true;

//    private int localControlPort = 37429;
//    private SocketAddress rpcAddr;
//    private byte[] rpcKey;

    private final byte[] ifacSalt = IFAC_SALT;
    private final AtomicLong lastDataPersist = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastCacheClean = new AtomicLong(0);

    private static final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2);

    /**
     * Initialises and starts a Reticulum instance. This must be
     * done before any other operations, and Reticulum will not
     * pass any traffic before being instantiated.
     *
     * @param configDir Full path to a Reticulum configuration directory.
     */
    public Reticulum(final String configDir) throws IOException {
        initConfig(configDir);
        transport = Transport.start(this);

        startLocalInterface();
        var ifList = initInterfaces();
        loadKnownDestinations();
        transport.getInterfaces().addAll(ifList);

//        rpcAddr = new InetSocketAddress(localIntefacePort);
//        rpcKey = fullHash(transport.getIdentity().getPrivateKey());

        // TODO: 07.03.2023 не уверен что нам надо делать в таком виде как в питоне...
//        if (isSharedInstance) {
//            rpcListener = new ServerSocket(localIntefacePort);
//        }
        //Запустить все интерфейсы
        ifList.stream()
                .filter(ConnectionInterface::isEnabled)
                .forEach(ConnectionInterface::launch);

        Runtime.getRuntime().addShutdownHook(new Thread(this::exitHandler));
        Signal.handle(new Signal("INT"), sig -> sigintHandler());
        Signal.handle(new Signal("TERM"), sig -> sigtermHandler());
    }

    public Reticulum getInstance() {
        return this;
    }

    /**
     * This exit handler is called whenever Reticulum is asked to
     * shut down, and will in turn call exit handlers in other
     * classes, saving necessary information to disk and carrying
     * out cleanup operations.
     */
    public void exitHandler() {
        transport.exitHandler();
        IdentityUtils.exitHandler();
    }

    private void sigintHandler() {
        transport.detachInterfaces();
        exit();
    }

    private void sigtermHandler() {
        transport.detachInterfaces();
        exit();
    }

    public void persistData() {
        transport.persistData();
        IdentityUtils.persistData();
    }

    private void cleanCaches() {
        log.trace("Cleaning resource and packet caches...");

        // Clean resource caches
        try (var streamPath = Files.walk(resourcePath)) {
            CLEAN_CONSUMER.accept(streamPath, RESOURCE_CACHE);
        } catch (IOException e) {
            log.error("Error while cleaning resources cache.", e);
        }

        // Clean packet caches
        try {
            Storage.getInstance().cleanPacketCache();
        } catch (Exception e) {
            log.error("Error while cleaning caches cache.", e);
        }
    }

    private List<ConnectionInterface> initInterfaces() {
        var interfaceList = new ArrayList<ConnectionInterface>();
        if (isFalse(isSharedInstance || isStandaloneInnstance)) {
            return interfaceList;
        }
        if (nonNull(config) && MapUtils.isNotEmpty(config.getInterfaces())) {
            for (ConnectionInterface connectionInterface : config.getInterfaces().values()) {
                var iface = (AbstractConnectionInterface) connectionInterface;

                if (isFalse(iface.isEnabled())) {
                    log.debug("Skipping disabled interface {}", iface.getInterfaceName());
                    continue;
                }

                if (interfaceList.stream().anyMatch(i -> i.getInterfaceName().equals(iface.getInterfaceName()))) {
                    log.error("The interface name {} was already used. Check your configuration file for errors!", iface.getInterfaceName());
                    panic();
                }

                if (isFalse(InterfaceUtils.initIFac(iface))) {
                    continue;
                }

                interfaceList.add(iface);
            }
            log.info("System interfaces are ready");
        }

        return interfaceList;
    }

    private void initConfig(String configDir) throws IOException {
        String configDirLocal;
        if (isNotBlank(configDir)) {
            configDirLocal = configDir;
        } else {
            if (Files.isDirectory(Path.of(ETC_DIR)) && Files.exists(Path.of(ETC_DIR, CONFIG_FILE_NAME))) {
                configDirLocal = ETC_DIR;
            } else if (
                    Files.isDirectory(Path.of(USER_HOME, ".config", "reticulum"))
                            && Files.exists(Path.of(ETC_DIR, ".config", "reticulum", CONFIG_FILE_NAME))
            ) {
                configDirLocal = Path.of(USER_HOME, ".config", "reticulum").toString();
            } else {
                configDirLocal = Path.of(USER_HOME, ".reticulum").toString();
            }
        }

        this.configPath = Path.of(configDirLocal);
        if (Files.notExists(configPath)) {
            Files.createDirectories(configPath);
        }

        this.storagePath = configPath.resolve( "storage");
        if (Files.notExists(storagePath)) {
            Files.createDirectories(storagePath);
        }

        this.resourcePath = storagePath.resolve("resources");
        if (Files.notExists(resourcePath)) {
            Files.createDirectories(resourcePath);
        }

        var configFile = configPath.resolve(CONFIG_FILE_NAME);
        if (Files.notExists(configFile)) {
            var defaultConfig = this.getClass().getClassLoader().getResourceAsStream("reticulum.default.yml");
            Files.copy(defaultConfig, configFile, REPLACE_EXISTING);
        }

        if (Files.isRegularFile(configFile)) {
            try {
                this.config = ConfigObj.initConfig(configFile);
            } catch (Exception e) {
                log.error("Could not parse the configuration at {}. \nCheck your configuration file for errors!", configFile);
                throw e;
            }
        } else {
            log.info("Could not load config file, creating default configuration file...");
            createDefaultConfig();
            this.config = ConfigObj.initConfig(configFile);
            log.info("Default config file created. Make any necessary changes in {}/config and restart Reticulum if needed.", configDirLocal);
        }

        log.info("Config loaded from {}", configFile);

        var reticulumConfig = config.getReticulum();
        shareInstance = Optional.ofNullable(reticulumConfig.getShareInstance()).orElse(shareInstance);
        localIntefacePort = Optional.ofNullable(reticulumConfig.getSharedInstancePort()).orElse(localIntefacePort);
//        localControlPort = Optional.ofNullable(reticulumConfig.getInstanceControlPort()).orElse(localControlPort);
        transportEnabled = Optional.ofNullable(reticulumConfig.getEnableTransport()).orElse(transportEnabled);
        panicOnIntefaceError = Optional.ofNullable(reticulumConfig.getPanicOnInterfaceError()).orElse(panicOnIntefaceError);
        useImplicitProof = Optional.ofNullable(reticulumConfig.getUseImplicitProof()).orElse(useImplicitProof);
    }

    private void startLocalInterface() {
        if (shareInstance) {
            try {
                var serverInterface = new LocalServerInterface(localIntefacePort);
                serverInterface.setOUT(true);
                serverInterface.start();
                transport.getInterfaces().add(serverInterface);

                isSharedInstance = true;
                log.debug("Started shared instance interface: {}", serverInterface.getInterfaceName());
                startJobs();
            } catch (Exception e) {
                try {
                    var localClientInterface = new LocalClientInterface("Local shared instance", localIntefacePort);
                    localClientInterface.setOUT(true);
                    localClientInterface.start();
                    transport.getInterfaces().add(localClientInterface);
                    isSharedInstance = false;
                    isStandaloneInnstance = false;
                    isConnectedToSharedInstance = true;
                    transportEnabled = false;
                    log.debug("Connected to locally available Reticulum instance via: {}", localClientInterface.getInterfaceName());
                } catch (IOException ex) {
                    log.error("Local shared instance appears to be running, but it could not be connected", e);
                    isSharedInstance = false;
                    isStandaloneInnstance = true;
                    isConnectedToSharedInstance = false;
                }
            }
        } else {
            isSharedInstance = false;
            isStandaloneInnstance = true;
            isConnectedToSharedInstance = false;
            startJobs();
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

    private void startJobs() {
        var defaultDelaySec = 5;
        scheduledExecutorService.scheduleAtFixedRate(
                () -> {
                    cleanCaches();
                    lastCacheClean.set(System.currentTimeMillis());
                }, defaultDelaySec,
                CLEAN_INTERVAL,
                SECONDS
        );
        scheduledExecutorService.scheduleAtFixedRate(() -> {
                    persistData();
                    lastDataPersist.set(System.currentTimeMillis());
                },
                defaultDelaySec,
                PERSIST_INTERVAL,
                SECONDS
        );
    }
}
