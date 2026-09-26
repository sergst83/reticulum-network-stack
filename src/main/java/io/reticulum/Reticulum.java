package io.reticulum;

import io.reticulum.config.ConfigObj;
import io.reticulum.interfaces.AbstractConnectionInterface;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.interfaces.InterfaceMode;
import io.reticulum.interfaces.discovery.InterfaceAnnouncer;
import io.reticulum.interfaces.discovery.InterfaceDiscovery;
import io.reticulum.interfaces.local.LocalClientInterface;
import io.reticulum.interfaces.local.LocalServerInterface;
import io.reticulum.storage.Storage;
import io.reticulum.utils.IdentityUtils;
import io.reticulum.utils.InterfaceUtils;
import io.reticulum.utils.Scheduler;
import org.apache.commons.codec.binary.Hex;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static io.reticulum.constant.ReticulumConstant.*;
import static io.reticulum.constant.TransportConstant.DEFAULT_GRAVITY;
import static io.reticulum.identity.IdentityKnownDestination.loadKnownDestinations;
import static io.reticulum.utils.CommonUtils.panic;
import static io.reticulum.utils.Scheduler.scheduler;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Objects.isNull;
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
 * <br>
 * As soon as an instance of this class is created, Reticulum will start
 * opening and configuring any hardware devices specified in the supplied
 * configuration.
 * <br>
 * Currently the first running instance must be kept running while other
 * local instances are connected, as the first created instance will
 * act as a master instance that directly communicates with external
 * hardware such as modems, TNCs and radios. If a master instance is
 * asked to exit, it will not exit until all client processes have
 * terminated (unless killed forcibly).
 * <br>
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
    /** Whether links may negotiate an MTU above the Reticulum default. */
    @Getter
    private boolean linkMtuDiscovery = true;
    /**
     * Whether auto-connected discovered interfaces permit announces into
     * internal-mode interfaces. Null means "not configured".
     */
    @Getter
    private Boolean autoconnectAnnouncesToInternal;

    /** Whether to look for interfaces announced on the network. Reference default is off. */
    @Getter
    private boolean discoverInterfaces = false;
    /** Minimum stamp value for a discovered interface to be remembered; null uses the default. */
    @Getter
    private Integer requiredDiscoveryValue;
    /**
     * Maximum number of auto-connected discovered interfaces. Zero disables
     * auto-connection, which is the reference default — a node does not dial out
     * to interfaces it finds on the network unless told to.
     */
    @Getter
    private int autoconnectDiscoveredInterfaces = 0;
    /** Interface mode for auto-connected discovered interfaces, or null for unset. */
    @Getter
    private InterfaceMode autoconnectInterfaceMode;
    /** Identity hashes from which interface discoveries are accepted. */
    @Getter
    private List<byte[]> interfaceDiscoverySources = new ArrayList<>();
    /** Name distinguishing this instance when several run on one system. */
    @Getter
    private String instanceName = "default";
    /** Running discovery manager, or null when discovery is not enabled. */
    @Getter
    private InterfaceDiscovery interfaceDiscovery;

    @Getter
    private Integer defaultArTarget;
    @Getter
    private Integer defaultArPenalty;
    @Getter
    private Integer defaultArGrace;

    @Getter
    private Integer defaultIcMaxHeldAnnounces;
    @Getter
    private Double defaultIcBurstHold;
    @Getter
    private Double defaultIcBurstFreqNew;
    @Getter
    private Double defaultIcBurstFreq;
    @Getter
    private Long defaultIcNewTime;
    @Getter
    private Long defaultIcBurstPenalty;
    @Getter
    private Long defaultIcHeldReleaseInterval;
    @Getter
    private Double defaultIcPrBurstFreqNew;
    @Getter
    private Double defaultIcPrBurstFreq;
    @Getter
    private Boolean defaultEgressControl;
    @Getter
    private Double defaultEcPrFreq;
    /** Gravity applied to interfaces that do not configure their own. */
    @Getter
    private Integer defaultGravity;
    /** Gravity applied to auto-connected discovered interfaces. */
    @Getter
    private Integer autoconnectInterfaceGravity;
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

    /**
     * Initialises and starts a Reticulum instance. This must be
     * done before any other operations, and Reticulum will not
     * pass any traffic before being instantiated.
     *
     * @param configDir Full path to a Reticulum configuration directory.
     * @throws IOException if there were problems reading/writing files from/to filesystem.
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

        // Order interfaces by preference once they are all registered
        transport.prioritizeInterfaces();

        startInterfaceDiscovery();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            transport.detachInterfaces();
            this.exitHandler();
        }));
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
        // Stop the shared scheduler last, after data has been persisted. This halts
        // Transport.jobs() and the periodic announce/persist work so the mesh stops
        // recovering once shutdown has begun (previously these kept running and the
        // node appeared to "keep building the mesh" during shutdown).
        Scheduler.shutdown();
    }

    public void persistData() {
        transport.persistData();
        IdentityUtils.persistData();
    }

    /**
     * Returns whether Transport is enabled for the running instance.
     * When Transport is enabled, Reticulum will route traffic for other peers,
     * respond to path requests and pass announces over the network.
     *
     * @return true if Transport is enabled, false if not.
     */
    public static boolean transportEnabled() {
        return Transport.getInstance().getOwner().isTransportEnabled();
    }

    /**
     * Returns whether proofs sent are explicit or implicit.
     *
     * @return true if the current configuration specifies implicit proofs, false if not.
     */
    public static boolean shouldUseImplicitProof() {
        return Transport.getInstance().getOwner().isUseImplicitProof();
    }

    /**
     * Returns whether automatic link MTU discovery is enabled for the running
     * instance. When enabled, a link initiator advertises the next-hop
     * interface's hardware MTU instead of the Reticulum default, which
     * significantly increases throughput over fast links.
     *
     * @return true if link MTU discovery is enabled
     */
    public static boolean linkMtuDiscovery() {
        return Transport.getInstance().getOwner().isLinkMtuDiscovery();
    }

    /**
     * Whether auto-connected discovered interfaces should permit their announces
     * to be propagated into internal-mode interfaces.
     *
     * @return true when configured on, otherwise null (meaning unconfigured)
     */
    /**
     * @return gravity for auto-connected discovered interfaces, or null if unset
     */
    public static Integer autoconnectInterfaceGravity() {
        return Transport.getInstance().getOwner().getAutoconnectInterfaceGravity();
    }

    public static Boolean autoconnectAnnouncesToInternal() {
        return Transport.getInstance().getOwner().getAutoconnectAnnouncesToInternal();
    }

    /**
     * @return whether discovered interfaces should be auto-connected. Off unless
     *         {@code autoconnect_discovered_interfaces} is configured above zero.
     */
    public static boolean shouldAutoconnectDiscoveredInterfaces() {
        return Transport.getInstance().getOwner().getAutoconnectDiscoveredInterfaces() > 0;
    }

    /**
     * @return the maximum number of auto-connected discovered interfaces
     */
    public static int maxAutoconnectedInterfaces() {
        return Transport.getInstance().getOwner().getAutoconnectDiscoveredInterfaces();
    }

    /**
     * @return the interface mode for auto-connected discovered interfaces, or null
     */
    public static InterfaceMode autoconnectInterfaceMode() {
        return Transport.getInstance().getOwner().getAutoconnectInterfaceMode();
    }

    /**
     * @return the required stamp value for a discovered interface, or null for none
     */
    public static Integer requiredDiscoveryValue() {
        return Transport.getInstance().getOwner().getRequiredDiscoveryValue();
    }

    /**
     * @return identity hashes from which interface discoveries are accepted
     */
    public static List<byte[]> interfaceDiscoverySources() {
        return Transport.getInstance().getOwner().getInterfaceDiscoverySources();
    }

    /**
     * Returns whether probe destination is enabled for the running instance.
     *
     * @return true if probe destination is enabled, false if not.
     */
    public static boolean probeDestinationEnabled() {
        return Transport.getInstance().getOwner().isAllowProbes();
    }

    /**
     * Returns whether this instance is acting as a shared instance (master)
     * for other local programs.
     *
     * @return true if this is a shared instance.
     */
    public boolean isSharedInstance() {
        return isSharedInstance;
    }

    /**
     * Returns the path table as a list of entries. Each entry contains the
     * destination hash, hops, via (next-hop), expiry, and interface.
     *
     * @param maxHops if non-null, only entries with hops &lt;= maxHops are included.
     * @return list of path table entries.
     */
    public List<PathEntry> getPathTable(Integer maxHops) {
        var result = new ArrayList<PathEntry>();
        for (var entry : transport.getDestinationTable().entrySet()) {
            var hops = entry.getValue();
            if (maxHops == null || hops.getPathLength() <= maxHops) {
                result.add(new PathEntry(
                        entry.getKey(),
                        hops.getTimestamp(),
                        hops.getVia(),
                        hops.getPathLength(),
                        hops.getExpires(),
                        nonNull(hops.getInterface()) ? hops.getInterface().toString() : null
                ));
            }
        }
        return result;
    }

    /**
     * Returns the path table including all entries.
     *
     * @return list containing every existing path tabel entry.
     */
    public List<PathEntry> getPathTable() {
        return getPathTable(null);
    }

    /**
     * Returns the next-hop destination hash for the given destination.
     *
     * @param destinationHash destination hash as byte[].
     * @return next-hop hash as byte[], or null if unknown.
     */
    public byte[] getNextHop(byte[] destinationHash) {
        return transport.nextHop(destinationHash);
    }

    /**
     * Returns the name of the interface used to reach the next hop for the given destination.
     *
     * @param destinationHash destination hash as byte[].
     * @return interface name string, or null if unknown.
     */
    public String getNextHopIfName(byte[] destinationHash) {
        var iface = transport.nextHopInterface(destinationHash);
        return nonNull(iface) ? iface.toString() : null;
    }

    /**
     * Returns the first-hop timeout in milliseconds for the given destination.
     *
     * @param destinationHash destination hash as byte[].
     * @return timeout in milliseconds.
     */
    public int getFirstHopTimeout(byte[] destinationHash) {
        return transport.firstHopTimeout(destinationHash);
    }

    /**
     * Returns the bitrate of the slowest currently online interface.
     *
     * @return lowest online interface bitrate in bits per second, or null if
     *         no online interface reports one.
     */
    public Integer getLowestInterfaceBitrate() {
        return transport.lowestInterfaceBitrate();
    }

    /**
     * Returns an estimate of a reasonable minimum path request timeout, covering
     * a full round trip for one MTU on the slowest currently online interface
     * plus per-hop grace.
     *
     * @return timeout in milliseconds, or 0 if it is unknown.
     */
    public long getMediumPathTimeout() {
        return transport.mediumPathTimeout();
    }

    /**
     * Requests a path to the destination and blocks until it is available or the
     * default path request timeout elapses.
     *
     * @param destinationHash destination hash as byte[].
     * @return true if a path to the destination is available.
     */
    public boolean awaitPath(byte[] destinationHash) {
        return transport.awaitPath(destinationHash);
    }

    /**
     * Requests a path to the destination and blocks until it is available or the
     * given timeout elapses.
     *
     * @param destinationHash destination hash as byte[].
     * @param timeoutMs       timeout in milliseconds.
     * @return true if a path to the destination is available.
     */
    public boolean awaitPath(byte[] destinationHash, long timeoutMs) {
        return transport.awaitPath(destinationHash, timeoutMs, null);
    }

    /**
     * Returns the number of currently tracked links (both pending and active).
     *
     * @return link count.
     */
    public int getLinkCount() {
        return transport.getLinkTable().size();
    }

    /**
     * Immediately expire the path to a destination, forcing re-discovery.
     *
     * @param destinationHash destination hash as byte[].
     * @return {@code true} if a path existed and was expired.
     */
    public boolean dropPath(byte[] destinationHash) {
        return transport.expirePath(destinationHash);
    }

    /**
     * Expire all paths that route through a given next-hop transport node.
     *
     * @param viaHash  the transport node's identity hash (16 bytes) to drop routes through.
     * @return         number of paths expired.
     */
    public int dropAllVia(byte[] viaHash) {
        var count = 0;
        for (var entry : transport.getDestinationTable().entrySet()) {
            if (java.util.Arrays.equals(entry.getValue().getVia(), viaHash)) {
                try {
                    transport.expirePath(org.apache.commons.codec.binary.Hex.decodeHex(entry.getKey()));
                    count++;
                } catch (Exception ignored) {
                    // Malformed key — skip
                }
            }
        }
        return count;
    }

    /**
     * A single entry in the path table, representing a known route to a destination.
     */
    @lombok.Value
    public static class PathEntry {
        String destinationHash;
        java.time.Instant timestamp;
        byte[] via;
        int hops;
        java.time.Instant expires;
        String interfaceName;
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

    /**
     * Starts on-network interface discovery when {@code discover_interfaces} is
     * configured. Off by default, matching the reference — a node does not look
     * for or dial out to interfaces it finds unless asked to.
     */
    private void startInterfaceDiscovery() {
        if (isFalse(discoverInterfaces)) {
            return;
        }
        if (isConnectedToSharedInstance) {
            return;
        }

        try {
            var stampValue = nonNull(requiredDiscoveryValue)
                    ? requiredDiscoveryValue
                    : InterfaceAnnouncer.DEFAULT_STAMP_VALUE;
            // The constructor registers the announce handler, reconnects known
            // interfaces and starts the monitor job itself.
            this.interfaceDiscovery = new InterfaceDiscovery(storagePath, stampValue, null);
            log.info("Interface discovery enabled, required stamp value is {}", stampValue);
        } catch (Exception e) {
            log.error("Could not start interface discovery", e);
        }
    }

    /**
     * Applies instance-wide announce rate and ingress control defaults to an
     * interface that did not configure its own.
     * <p>
     * The announce rate fields default to null on an interface, so an
     * instance-level value fills them in. The ingress control fields already
     * carry the reference defaults, so an instance-level value overrides them
     * only when explicitly configured.
     */
    private void applyInterfaceDefaults(final AbstractConnectionInterface iface) {
        if (isNull(iface.getAnnounceRateTarget()) && nonNull(defaultArTarget)) {
            iface.setAnnounceRateTarget(defaultArTarget);
        }
        if (isNull(iface.getAnnounceRatePenalty()) && nonNull(defaultArPenalty)) {
            iface.setAnnounceRatePenalty(defaultArPenalty);
        }
        if (isNull(iface.getAnnounceRateGrace()) && nonNull(defaultArGrace)) {
            iface.setAnnounceRateGrace(defaultArGrace);
        }

        if (nonNull(defaultIcMaxHeldAnnounces)) {
            iface.setIcMaxHeldAnnounces(defaultIcMaxHeldAnnounces);
        }
        if (nonNull(defaultIcBurstHold)) {
            iface.setIcBurstHold(defaultIcBurstHold);
        }
        if (nonNull(defaultIcBurstFreqNew)) {
            iface.setIcBurstFreqNew(defaultIcBurstFreqNew);
        }
        if (nonNull(defaultIcBurstFreq)) {
            iface.setIcBurstFreq(defaultIcBurstFreq);
        }
        if (nonNull(defaultIcNewTime)) {
            iface.setIcNewTime(defaultIcNewTime);
        }
        if (nonNull(defaultIcBurstPenalty)) {
            iface.setIcBurstPenalty(defaultIcBurstPenalty);
        }
        if (nonNull(defaultIcHeldReleaseInterval)) {
            iface.setIcHeldReleaseInterval(defaultIcHeldReleaseInterval);
        }
        if (nonNull(defaultIcPrBurstFreqNew)) {
            iface.setIcPrBurstFreqNew(defaultIcPrBurstFreqNew);
        }
        if (nonNull(defaultIcPrBurstFreq)) {
            iface.setIcPrBurstFreq(defaultIcPrBurstFreq);
        }
        if (nonNull(defaultEgressControl)) {
            iface.setEgressControl(defaultEgressControl);
        }
        if (nonNull(defaultEcPrFreq)) {
            iface.setEcPrFreq(defaultEcPrFreq);
        }
        if (iface.getGravity() == DEFAULT_GRAVITY && nonNull(defaultGravity)) {
            iface.setGravity(defaultGravity);
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

                applyInterfaceDefaults(iface);
                // Derive the hardware MTU from the (possibly config-overridden)
                // bitrate, as the reference does once an interface is configured
                // (RNS/Reticulum.py:948).
                iface.optimiseMtu();
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
                            && Files.exists(Path.of(USER_HOME, ".config", "reticulum", CONFIG_FILE_NAME))
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
        linkMtuDiscovery = Optional.ofNullable(reticulumConfig.getLinkMtuDiscovery()).orElse(linkMtuDiscovery);
        autoconnectAnnouncesToInternal = Optional.ofNullable(reticulumConfig.getAutoconnectAnnouncesToInternal())
                .orElse(autoconnectAnnouncesToInternal);

        discoverInterfaces = Optional.ofNullable(reticulumConfig.getDiscoverInterfaces()).orElse(discoverInterfaces);
        instanceName = Optional.ofNullable(reticulumConfig.getInstanceName()).orElse(instanceName);

        // A stamp value of zero or less means "no requirement", as in the reference
        var configuredStampValue = reticulumConfig.getRequiredDiscoveryValue();
        requiredDiscoveryValue = nonNull(configuredStampValue) && configuredStampValue > 0 ? configuredStampValue : null;

        // Absent or non-positive leaves auto-connection disabled
        autoconnectDiscoveredInterfaces = Optional.ofNullable(reticulumConfig.getAutoconnectDiscoveredInterfaces())
                .filter(count -> count > 0)
                .orElse(0);

        autoconnectInterfaceMode = parseInterfaceMode(reticulumConfig.getAutoconnectInterfaceMode());
        interfaceDiscoverySources = parseIdentityHashes(
                reticulumConfig.getInterfaceDiscoverySources(), "interface_discovery_sources");

        defaultArTarget = reticulumConfig.getDefaultArTarget();
        defaultArPenalty = reticulumConfig.getDefaultArPenalty();
        defaultArGrace = reticulumConfig.getDefaultArGrace();

        defaultIcMaxHeldAnnounces = reticulumConfig.getIcMaxHeldAnnounces();
        defaultIcBurstHold = reticulumConfig.getIcBurstHold();
        defaultIcBurstFreqNew = reticulumConfig.getIcBurstFreqNew();
        defaultIcBurstFreq = reticulumConfig.getIcBurstFreq();
        defaultIcNewTime = reticulumConfig.getIcNewTime();
        defaultIcBurstPenalty = reticulumConfig.getIcBurstPenalty();
        defaultIcHeldReleaseInterval = reticulumConfig.getIcHeldReleaseInterval();
        defaultIcPrBurstFreqNew = reticulumConfig.getIcPrBurstFreqNew();
        defaultIcPrBurstFreq = reticulumConfig.getIcPrBurstFreq();
        defaultEgressControl = reticulumConfig.getEgressControl();
        defaultEcPrFreq = reticulumConfig.getEcPrFreq();
        defaultGravity = reticulumConfig.getDefaultGravity();
        autoconnectInterfaceGravity = reticulumConfig.getAutoconnectInterfaceGravity();
    }

    /**
     * Parses a configured interface mode name, returning null when unset or
     * unrecognised — the reference leaves the mode unset rather than failing.
     */
    private static InterfaceMode parseInterfaceMode(final String modeName) {
        if (isNull(modeName) || modeName.isBlank()) {
            return null;
        }

        try {
            return InterfaceMode.parseName(modeName);
        } catch (Exception e) {
            log.warn("Unrecognised interface mode '{}' in configuration, ignoring", modeName);

            return null;
        }
    }

    /**
     * Parses a list of hex identity hashes, rejecting anything of the wrong
     * length rather than silently accepting it (mirrors RNS/Reticulum.py:598-605).
     */
    @SneakyThrows
    private static List<byte[]> parseIdentityHashes(final List<String> hexHashes, final String option) {
        var hashes = new ArrayList<byte[]>();
        if (isNull(hexHashes)) {
            return hashes;
        }

        var expectedLength = TRUNCATED_HASHLENGTH / 8 * 2;
        for (var hexHash : hexHashes) {
            if (isNull(hexHash) || hexHash.length() != expectedLength) {
                throw new IllegalArgumentException(String.format(
                        "Identity hash '%s' for %s is invalid, must be %d hexadecimal characters (%d bytes)",
                        hexHash, option, expectedLength, expectedLength / 2));
            }
            hashes.add(Hex.decodeHex(hexHash));
        }

        return hashes;
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
        scheduler.scheduleAtFixedRate(
                () -> {
                    cleanCaches();
                    lastCacheClean.set(System.currentTimeMillis());
                }, defaultDelaySec,
                CLEAN_INTERVAL,
                SECONDS
        );
        scheduler.scheduleAtFixedRate(() -> {
                    persistData();
                    lastDataPersist.set(System.currentTimeMillis());
                },
                defaultDelaySec,
                PERSIST_INTERVAL,
                SECONDS
        );
    }
}
