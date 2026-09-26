package io.reticulum.interfaces;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.reticulum.Transport;
import io.reticulum.constant.TransportConstant;
import io.reticulum.identity.Identity;
import io.reticulum.packet.Packet;
import io.reticulum.transport.AnnounceQueueEntry;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.reticulum.constant.ReticulumConstant;

import static io.reticulum.constant.ReticulumConstant.ANNOUNCE_CAP;
import static io.reticulum.constant.ReticulumConstant.MINIMUM_BITRATE;
import static io.reticulum.constant.ReticulumConstant.QUEUED_ANNOUNCE_LIFE;
import static io.reticulum.interfaces.InterfaceMode.MODE_FULL;
import static java.math.BigInteger.ZERO;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Slf4j
/*
 * Only members carrying an explicit @JsonProperty/@JsonAlias are treated as
 * configuration.
 *
 * This class extends Thread, so with Jackson's default auto-detection every
 * inherited Thread property became a candidate — and building a deserializer
 * then tried to force access to JDK internals such as Thread.interrupted,
 * throwing InaccessibleObjectException under JPMS. The effect was that
 * Reticulum could not load a config declaring an AutoInterface or
 * TCPServerInterface at all without --add-opens java.base/java.lang and
 * java.base/java.net, which every embedding application had to carry.
 *
 * Explicit-only detection fixes that at the root: nothing inherited, and
 * nothing added by a future JDK, can leak into the config model.
 */
@JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.NONE,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE)
public abstract class AbstractConnectionInterface extends Thread implements ConnectionInterface {

    /**
     * Shared, daemon-backed scheduler for pacing announce-queue processing across all
     * interfaces. Previously {@link #processAnnounceQueue()} created a brand-new
     * {@code newSingleThreadScheduledExecutor()} on every call with
     * {@code scheduleAtFixedRate} — a non-daemon, never-shut-down executor that both
     * kept firing forever and multiplied on each pass, leaking threads (observed as a
     * large, growing set of {@code pool-*} threads) and preventing clean JVM exit on
     * shutdown. A single shared daemon executor with a one-shot {@code schedule} is
     * sufficient: the method re-schedules itself while the queue is non-empty.
     */
    private static final java.util.concurrent.ScheduledExecutorService ANNOUNCE_QUEUE_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                var thread = new Thread(runnable, "announce-queue");
                thread.setDaemon(true);
                return thread;
            });

    @JsonProperty("outgoing")
    protected boolean OUT = true;
    protected boolean IN = false;
    protected boolean FWD = false;
    protected boolean RPT = false;
    protected AtomicBoolean online = new AtomicBoolean(false);
    protected AtomicInteger clients = new AtomicInteger(0);
    protected String interfaceName;
    protected AtomicReference<BigInteger> rxb = new AtomicReference<>(ZERO);
    protected AtomicReference<BigInteger> txb = new AtomicReference<>(ZERO);
    protected AtomicReference<Instant> icHeldRelease = new AtomicReference<>();

    protected Identity identity;
    @JsonProperty("enabled")
    protected boolean enabled;
    protected byte[] ifacKey;
    protected byte[] ifacSignature;
    protected final Instant created = Instant.now();

    /**
     * Rolling windows of recent outgoing/incoming announce timestamps, newest first,
     * used only to estimate announce frequency. These MUST stay bounded: Python RNS
     * declares them as {@code deque(maxlen=...)}, but this port used an unbounded
     * CopyOnWriteArrayList. On a server interface the parent aggregates announces from
     * every spawned client interface, so an untrimmed list grew without bound — driving
     * an OutOfMemoryError (and O(n^2) array copies) on transport/server nodes. Always
     * add via {@link #recordSentAnnounce()} / {@link #recordReceivedAnnounce()} so the
     * cap is enforced.
     */
    protected List<Instant> oaFreqDeque = new CopyOnWriteArrayList<>();
    protected List<Instant> iaFreqDeque = new CopyOnWriteArrayList<>();
    /** Rolling windows of recent path request timestamps, newest first. */
    protected List<Instant> ipFreqDeque = new CopyOnWriteArrayList<>();
    protected List<Instant> opFreqDeque = new CopyOnWriteArrayList<>();

    /** Announce ingress burst state. */
    protected volatile boolean icBurstActive = false;
    protected volatile Instant icBurstActivated = Instant.EPOCH;
    protected volatile Instant icBurstSustained = Instant.EPOCH;

    /** Path request ingress burst state. */
    protected volatile boolean icPrBurstActive = false;
    protected volatile Instant icPrBurstActivated = Instant.EPOCH;
    protected volatile Instant icPrBurstSustained = Instant.EPOCH;
    protected volatile int icPrBurstCooldown = 0;

    /** Number of samples retained for incoming announce frequency (Interface.IA_FREQ_SAMPLES). */
    protected static final int IA_FREQ_SAMPLES = 48;
    /** Number of samples retained for outgoing announce frequency (Interface.OA_FREQ_SAMPLES). */
    protected static final int OA_FREQ_SAMPLES = 48;
    /** Minimum number of samples before an incoming frequency is reported at all. */
    protected static final int IC_DEQUE_MIN_SAMPLE = 2;
    /** Lowest announce frequency tracked, in Hz. */
    protected static final double AR_MINFREQ_HZ = 0.1;
    /** Sample decay window in seconds: samples older than this are aged out one per call. */
    protected static final double AR_FREQ_DECAY = 1 / AR_MINFREQ_HZ;
    /** Number of samples retained for incoming path request frequency. */
    protected static final int IP_FREQ_SAMPLES = 48;
    /** Number of samples retained for outgoing path request frequency. */
    protected static final int OP_FREQ_SAMPLES = 48;
    /** Lowest path request frequency tracked, in Hz. */
    protected static final double PR_MINFREQ_HZ = 0.1;
    /** Path request sample decay window in seconds. */
    protected static final double PR_FREQ_DECAY = 1 / PR_MINFREQ_HZ;
    /** Minimum outgoing samples before egress limiting can engage. */
    protected static final int EC_BURST_MIN_SAMPLES = 2;
    /** Rounds an active path request burst stays engaged after the rate drops. */
    protected static final int IC_PR_BURST_COOLDOWN = 3;

    // @JsonProperty is required, not decorative: this class uses explicit-only
    // @JsonAutoDetect, and @JsonAlias only adds extra names to a property that is
    // already discoverable. With the alias alone the key never bound, so every
    // interface silently stayed MODE_FULL and no node could be configured as a
    // gateway, access point, boundary or roaming peer.
    @JsonProperty("interface_mode")
    @JsonAlias({"mode"})
    protected InterfaceMode interfaceMode = MODE_FULL;

    /** See {@link ConnectionInterface#isAnnouncesFromInternal()}. */
    @JsonProperty("announces_from_internal")
    protected boolean announcesFromInternal = true;

    /** See {@link ConnectionInterface#getAnnouncesToInternal()}. */
    @JsonProperty("announces_to_internal")
    protected Boolean announcesToInternal;

    /** See {@link ConnectionInterface#isRecursivePrs()}. */
    @JsonProperty("recursive_prs")
    protected boolean recursivePrs = false;

    @JsonProperty("ifac_size")
    protected Integer ifacSize;

    /** Set by InterfaceDiscovery when this interface is auto-connected. Hash of (reachableOn:port). */
    protected byte[] autoconnectHash;
    /** Hex network_id of the discovery source that triggered this auto-connection. */
    protected String autoconnectSource;
    /** Epoch-second timestamp when an auto-connected interface first went offline; null if online. */
    protected Long autoconnectDown;

    @JsonProperty("networkname")
    @JsonAlias({"network_name"})
    protected String ifacNetName;

    @JsonProperty("passphrase")
    @JsonAlias({"pass_phrase"})
    protected String ifacNetKey;

    @JsonProperty("bitrate")
    protected Integer bitrate;

    /**
     * Largest frame this interface can carry, in bytes; {@code null} means the
     * interface declares no hardware MTU.
     * <p>
     * Mirrors the reference's {@code self.HW_MTU} instance attribute: subclasses
     * seed it with their per-class ceiling, and {@link #optimiseMtu()} then
     * recomputes it from the bitrate for interfaces that autoconfigure.
     */
    protected Integer hwMtu = ReticulumConstant.MTU;

    /** Count of protocol violations seen on this interface. */
    protected final java.util.concurrent.atomic.AtomicLong protocolViolations =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Preference weight used to break ties between equally good paths, and to
     * order interfaces. See {@link ConnectionInterface#getGravity()}.
     */
    @JsonProperty("gravity")
    protected int gravity = 0;

    @JsonProperty("announce_rate_target")
    protected Integer announceRateTarget;

    @JsonProperty("announce_rate_grace")
    protected Integer announceRateGrace;

    @JsonProperty("announce_rate_penalty")
    protected Integer announceRatePenalty;

    @JsonProperty("ingress_control")
    protected Boolean ingressControl = true;

    @JsonProperty("ic_max_held_announces")
    protected int icMaxHeldAnnounces = 256;

    @JsonProperty("ic_burst_hold")
    protected Double icBurstHold = 15.0;

    @JsonProperty("ic_burst_freq_new")
    protected Double icBurstFreqNew = 3.0;

    @JsonProperty("ic_burst_freq")
    protected Double icBurstFreq = 10.0;

    @JsonProperty("ic_new_time")
    protected long icNewTime = 2 * 60 * 60; //seconds

    @JsonProperty("ic_burst_penalty")
    protected long icBurstPenalty = 15; //seconds

    @JsonProperty("ic_held_release_interval")
    protected long icHeldReleaseInterval = 5; //seconds

    @JsonProperty("ic_pr_burst_freq_new")
    protected Double icPrBurstFreqNew = 3.0;

    @JsonProperty("ic_pr_burst_freq")
    protected Double icPrBurstFreq = 8.0;

    /** Whether outgoing path requests are rate limited. Off by default, as in the reference. */
    @JsonProperty("egress_control")
    protected Boolean egressControl = false;

    @JsonProperty("ec_pr_freq")
    protected Double ecPrFreq = 5.0;

    @JsonProperty("announce_cap")
    protected Double announceCap = ANNOUNCE_CAP / 100;
    protected Instant announceAllowedAt;
    protected Queue<AnnounceQueueEntry> announceQueue = new ConcurrentLinkedQueue<>();
    protected Map<String, Packet> heldAnnounces = new ConcurrentHashMap<>();

    @Override
    public boolean isOnline() {
        return online.get();
    }

    public void setIfacNetName(String newIfacNetname) {
        if (StringUtils.isNotBlank(newIfacNetname)) {
            ifacNetName = newIfacNetname;
        }
    }

    public void setIfacNetKey(String newIfacNetkey) {
        if (StringUtils.isNotBlank(newIfacNetkey)) {
            ifacNetKey = newIfacNetkey;
        }
    }

    /**
     * Copy the traffic-control settings of a parent server interface onto an
     * interface spawned for an accepted connection.
     * <p>
     * A server interface never receives anything itself — the spawned child
     * does — so every per-interface limit configured on the parent has to be
     * carried across or it simply does not apply. The reference does this
     * attribute by attribute at each spawn site
     * ({@code RNS/Interfaces/TCPInterface.py:596-608},
     * {@code BackboneInterface.py:701-713}); collected here so the three Java
     * spawn sites cannot drift apart.
     * <p>
     * This was missing entirely: {@code ingress_control}, every {@code ic_*} and
     * {@code ec_*} tunable, and {@code gravity} were silently discarded, so
     * configuring any of them on a TCPServerInterface or BackboneServerInterface
     * had no effect at all.
     */
    public void inheritTrafficControl(AbstractConnectionInterface parent) {
        this.ingressControl = parent.ingressControl;
        this.icMaxHeldAnnounces = parent.icMaxHeldAnnounces;
        this.icBurstHold = parent.icBurstHold;
        this.icBurstFreq = parent.icBurstFreq;
        this.icBurstFreqNew = parent.icBurstFreqNew;
        this.icNewTime = parent.icNewTime;
        this.icBurstPenalty = parent.icBurstPenalty;
        this.icHeldReleaseInterval = parent.icHeldReleaseInterval;

        this.egressControl = parent.egressControl;
        this.ecPrFreq = parent.ecPrFreq;
        this.icPrBurstFreqNew = parent.icPrBurstFreqNew;
        this.icPrBurstFreq = parent.icPrBurstFreq;

        this.gravity = parent.gravity;
    }

    @Override
    public void protocolViolation(String description) {
        protocolViolations.incrementAndGet();
        log.debug("Protocol violation on {}: {}", this, description);
    }

    @Override
    public Integer getHwMtu() {
        return hwMtu;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Transcribed from {@code Interface.optimise_mtu()}. Note that this
     * <em>overwrites</em> the per-class ceiling rather than capping against it,
     * exactly as the reference does — a 1 Gbps interface reaches 524288 even
     * where its class constant is lower.
     */
    @Override
    public void optimiseMtu() {
        if (isAutoconfigureMtu()) {
            this.hwMtu = ConnectionInterface.optimisedMtu(this.bitrate);
            log.debug("{} hardware MTU set to {}", this, this.hwMtu);
        }
    }

    public void setBitrate(int bitrate) {
        if (bitrate >= MINIMUM_BITRATE) {
            this.bitrate = bitrate;
        }
    }

    public void setAnnounceRateTarget(Integer newAnnounceRateTarget) {
        if (requireNonNullElse(newAnnounceRateTarget, 0) > 0) {
            announceRateTarget = newAnnounceRateTarget;
        }
    }

    public void setAnnounceRateGrace(Integer newAnnounceRateGrace) {
        if (requireNonNullElse(newAnnounceRateGrace, 0) > 0) {
            this.announceRateGrace = newAnnounceRateGrace;
        }
    }

    public void setAnnounceRatePenalty(Integer newAnnounceRatePenalty) {
        if (requireNonNullElse(newAnnounceRatePenalty, 0) > 0) {
            this.announceRatePenalty = newAnnounceRatePenalty;
        }
    }

    public Integer getAnnounceRateGrace() {
        if (nonNull(getAnnounceRateTarget()) && isNull(announceRateGrace)) {
            announceRateGrace = 0;
        }

        return announceRateGrace;
    }

    public Integer getAnnounceRatePenalty() {
        if (nonNull(getAnnounceRateTarget()) && isNull(announceRatePenalty)) {
            announceRatePenalty = 0;
        }

        return announceRatePenalty;
    }

    public void setAnnounceCap(double newAnnounceCap) {
        if (newAnnounceCap > 0 && newAnnounceCap < 100) {
            this.announceCap = newAnnounceCap / 100;
        }
    }

    public String getInterfaceName() {
        return String.format(this.getClass().getSimpleName() + "[%s]", interfaceName);
    }

    @Override
    public InterfaceMode getMode() {
        return interfaceMode;
    }

    @Override
    public synchronized void processAnnounceQueue() {
        if (announceCap == 0) {
            announceCap = ANNOUNCE_CAP;
        }

        if (CollectionUtils.isNotEmpty(announceQueue)) {
            try {
                var now = Instant.now();
                var stale = new LinkedList<AnnounceQueueEntry>();
                for (AnnounceQueueEntry a : announceQueue) {
                    if (now.isAfter(a.getTime().plusSeconds(QUEUED_ANNOUNCE_LIFE))) {
                        stale.add(a);
                    }
                }

                for (AnnounceQueueEntry s : stale) {
                    announceQueue.remove(s);
                }

                if (!announceQueue.isEmpty()) {
                    var minHops = announceQueue.stream()
                            .min(Comparator.comparingInt(AnnounceQueueEntry::getHops))
                            .map(AnnounceQueueEntry::getHops)
                            .get();
                    var entries = announceQueue.stream()
                            .filter(announceQueueEntry -> announceQueueEntry.getHops() == minHops)
                            .sorted(Comparator.comparing(AnnounceQueueEntry::getTime))
                            .collect(toList());
                    var selected = entries.get(0);

                    var txTime = selected.getRaw().length * 8 / bitrate;
                    var waitTime = (long) (txTime / announceCap);
                    announceAllowedAt = Instant.now().plusSeconds(waitTime);

                    processOutgoing(selected.getRaw());

                    announceQueue.remove(selected);

                    if (!announceQueue.isEmpty()) {
                        // One-shot: process the next queued announce after waitTime. This method
                        // re-schedules itself as long as entries remain, so a repeating
                        // scheduleAtFixedRate (on a fresh, leaked executor) is neither needed nor
                        // correct — it compounded into many orphaned non-daemon timers.
                        ANNOUNCE_QUEUE_EXECUTOR.schedule(this::processAnnounceQueue, waitTime, TimeUnit.SECONDS);
                    }
                }
            } catch (Exception e) {
                announceQueue.clear();
                log.error("Error while processing announce queue on {}", getInterfaceName());
                log.error("The announce queue for this interface has been cleared.");
            }
        }
    }

    @Override
    public void sentAnnounce(boolean fromSpawned) {
        recordSentAnnounce();
        if (nonNull(getParentInterface())) {
            getParentInterface().sentAnnounce(true);
        }
    }

    @Override
    public void receivedAnnounce(boolean fromSpawned) {
        recordReceivedAnnounce();
        if (nonNull(getParentInterface())) {
            getParentInterface().receivedAnnounce(true);
        }
    }

    /** Prepend now() to the outgoing-announce window and trim it to {@link #OA_FREQ_SAMPLES}. */
    protected void recordSentAnnounce() {
        oaFreqDeque.add(0, Instant.now());
        trimFreqDeque(oaFreqDeque, OA_FREQ_SAMPLES);
    }

    /** Prepend now() to the incoming-announce window and trim it to {@link #IA_FREQ_SAMPLES}. */
    protected void recordReceivedAnnounce() {
        iaFreqDeque.add(0, Instant.now());
        trimFreqDeque(iaFreqDeque, IA_FREQ_SAMPLES);
    }

    private static void trimFreqDeque(List<Instant> deque, int maxSamples) {
        // Newest entries are prepended at index 0, so drop from the tail (oldest first).
        while (deque.size() > maxSamples) {
            deque.remove(deque.size() - 1);
        }
    }

    /**
     * Oldest retained sample, or null if the window is empty. These windows are
     * stored newest-first, so the oldest entry is the last one — the mirror of
     * the reference implementation's {@code deque[0]}.
     */
    private static Instant oldestSample(List<Instant> deque) {
        return deque.isEmpty() ? null : deque.get(deque.size() - 1);
    }

    /**
     * Frequency in Hz over the retained window: sample count divided by the span
     * back to the oldest sample. Mirrors {@code Interface.incoming_announce_frequency}
     * and friends (RNS/Interfaces/Interface.py:346-366), including the decay step
     * that drops one aged sample per call.
     */
    private static double sampleFrequency(List<Instant> deque, int minSamples) {
        return sampleFrequency(deque, minSamples, AR_FREQ_DECAY, 0);
    }

    /**
     * @param decay     seconds after which one aged sample is dropped per call
     * @param preemptive counted into the rate without being recorded, so an
     *                   egress check can ask "what would the rate be if I sent now"
     */
    private static double sampleFrequency(List<Instant> deque, int minSamples, double decay, int preemptive) {
        var n = deque.size();
        if (n <= minSamples) {
            return 0;
        }
        n += preemptive;

        var oldest = oldestSample(deque);
        // Nanosecond resolution: the reference uses float seconds from time.time(),
        // and truncating to milliseconds would report 0 Hz for bursts arriving
        // inside the same millisecond — exactly the case the controls exist for.
        var span = Duration.between(oldest, Instant.now()).toNanos() / 1_000_000_000.0;

        if (span > decay) {
            deque.remove(deque.size() - 1);
        }
        if (span <= 0) {
            return 0;
        }

        return n / span;
    }

    protected double incomingPrFrequency() {
        return sampleFrequency(ipFreqDeque, IC_DEQUE_MIN_SAMPLE, PR_FREQ_DECAY, 0);
    }

    protected double outgoingPrFrequency(boolean preemptive) {
        return sampleFrequency(opFreqDeque, 1, PR_FREQ_DECAY, preemptive ? 1 : 0);
    }

    /** Records an inbound path request, and propagates it to a parent interface. */
    @Override
    public void receivedPathRequest() {
        ipFreqDeque.add(0, Instant.now());
        trimFreqDeque(ipFreqDeque, IP_FREQ_SAMPLES);
        if (nonNull(getParentInterface())) {
            getParentInterface().receivedPathRequest();
        }
    }

    /** Records an outbound path request, and propagates it to a parent interface. */
    @Override
    public void sentPathRequest() {
        opFreqDeque.add(0, Instant.now());
        trimFreqDeque(opFreqDeque, OP_FREQ_SAMPLES);
        if (nonNull(getParentInterface())) {
            getParentInterface().sentPathRequest();
        }
    }

    /**
     * Whether inbound announces should currently be held rather than processed.
     * <p>
     * This returned false unconditionally, so announce ingress control never
     * engaged despite all of its configuration being present: an interface under
     * an announce storm had no protection at all. Mirrors
     * {@code Interface.should_ingress_limit} (RNS/Interfaces/Interface.py:188).
     * <p>
     * Once a burst is detected the limit stays engaged until the rate has been
     * below the threshold for {@code ic_burst_hold} seconds, measured from both
     * activation and the last time the rate was sustained.
     */
    @Override
    public boolean shouldIngressLimit() {
        if (isFalse(Boolean.TRUE.equals(ingressControl))) {
            return false;
        }

        var freqThreshold = age() < icNewTime ? icBurstFreqNew : icBurstFreq;
        var iaFreq = incomingAnnounceFrequency();
        var now = Instant.now();

        if (icBurstActive) {
            var heldLongEnough = now.isAfter(icBurstActivated.plusSeconds(icBurstHold.longValue()))
                    && now.isAfter(icBurstSustained.plusSeconds(icBurstHold.longValue()));

            if (iaFreq < freqThreshold && heldLongEnough) {
                if (iaFreqDeque.size() >= IC_DEQUE_MIN_SAMPLE) {
                    icBurstActive = false;
                }
            } else if (iaFreq >= freqThreshold) {
                icBurstSustained = now;
            }

            return true;
        }

        if (iaFreq > freqThreshold) {
            icBurstActive = true;
            icBurstActivated = now;
            icBurstSustained = now;
            icHeldRelease.set(now.plusSeconds(icBurstPenalty));

            return true;
        }

        return false;
    }

    /**
     * Whether inbound path requests should currently be rate limited.
     * <p>
     * Mirrors {@code Interface.should_ingress_limit_pr}
     * (RNS/Interfaces/Interface.py:213). Unlike the announce variant this uses a
     * cooldown counter rather than a sample-count check to leave the burst state.
     */
    @Override
    public boolean shouldIngressLimitPr() {
        if (isFalse(Boolean.TRUE.equals(ingressControl))) {
            return false;
        }

        var freqThreshold = age() < icNewTime ? icPrBurstFreqNew : icPrBurstFreq;
        var ipFreq = incomingPrFrequency();
        var now = Instant.now();

        if (icPrBurstActive) {
            var heldLongEnough = now.isAfter(icPrBurstActivated.plusSeconds(icBurstHold.longValue()))
                    && now.isAfter(icPrBurstSustained.plusSeconds(icBurstHold.longValue()));

            if (ipFreq < freqThreshold && heldLongEnough) {
                if (icPrBurstCooldown <= 0) {
                    icPrBurstActive = false;
                } else {
                    icPrBurstCooldown--;
                }
            } else {
                icPrBurstCooldown = IC_PR_BURST_COOLDOWN;
                if (ipFreq >= freqThreshold) {
                    icPrBurstSustained = now;
                }
            }

            return true;
        }

        if (ipFreq > freqThreshold) {
            icPrBurstActive = true;
            icPrBurstActivated = now;
            icPrBurstSustained = now;
            icPrBurstCooldown = IC_PR_BURST_COOLDOWN;

            return true;
        }

        return false;
    }

    /**
     * Whether an outgoing path request should be suppressed to stay within this
     * interface's egress budget. Mirrors
     * {@code Interface.should_egress_limit_pr} (RNS/Interfaces/Interface.py:240).
     * <p>
     * The frequency is evaluated preemptively — as if the request had already
     * been sent — so the interface does not exceed the budget and then notice.
     */
    @Override
    public boolean shouldEgressLimitPr() {
        if (isFalse(Boolean.TRUE.equals(egressControl))) {
            return false;
        }

        if (outgoingPrFrequency(true) > ecPrFreq) {
            return opFreqDeque.size() >= EC_BURST_MIN_SAMPLES;
        }

        return false;
    }

    @Override
    public void holdAnnounce(Packet announcePacket) {
        var hash = Hex.encodeHexString(announcePacket.getDestinationHash());
        if (heldAnnounces.containsKey(hash)) {
            heldAnnounces.put(hash, announcePacket);
        } else if (isFalse((MapUtils.size(heldAnnounces) >= icMaxHeldAnnounces))) {
            heldAnnounces.put(hash, announcePacket);
        }
    }

    @Override
    public void processHeldAnnounces() {
        try {
            // No burst-active gate here, deliberately: the reference drains held
            // announces once the rate has fallen below the threshold and the
            // penalty period has elapsed, even while the burst is still engaged
            // — that is the whole purpose of ic_held_release. Gating on
            // shouldIngressLimit() also called it a second time per sweep, and it
            // mutates burst state (RNS/Interfaces/Interface.py:277).
            if (MapUtils.size(heldAnnounces) > 0 && Instant.now().isAfter(icHeldRelease.get())) {
                var freqThreshold = age() < icNewTime ? icBurstFreqNew : icBurstFreq;
                var iaFreq = incomingAnnounceFrequency();
                if (iaFreq < freqThreshold) {
                    var selectedAnnouncePacket = (Packet) null;
                    var minHops = TransportConstant.PATHFINDER_M;
                    for (String destinationHash : heldAnnounces.keySet()) {
                        var announcePacket = heldAnnounces.get(destinationHash);
                        if (announcePacket.getHops() < minHops) {
                            minHops = announcePacket.getHops();
                            selectedAnnouncePacket = announcePacket;
                        }
                    }

                    if (nonNull(selectedAnnouncePacket)) {
                        var announcePacket = selectedAnnouncePacket;
                        log.trace("Releasing held announce packet {} from {}", selectedAnnouncePacket, this);
                        icHeldRelease.set(Instant.now().plusSeconds(icHeldReleaseInterval));
                        heldAnnounces.remove(Hex.encodeHexString(selectedAnnouncePacket.getDestinationHash()));
                        runAsync(() -> Transport.getInstance().inbound(announcePacket.getRaw(), announcePacket.getReceivingInterface()));
                    }
                }
            }
        } catch (Exception e) {
            log.error("An error occurred while processing held announces for {}", this, e);
        }
    }

    /**
     * Seconds since this interface was created.
     * <p>
     * Mirrors {@code Interface.age()} ({@code time.time() - self.created}). The
     * arguments were the wrong way round, so this returned a negative number
     * that grew more negative with time. Every caller compares it against
     * {@code icNewTime}, so an interface was permanently classified as "new" and
     * ingress control used the stricter new-interface thresholds forever —
     * roughly three times more aggressive than the reference.
     *
     * @return seconds
     */
    protected long age() {
        return Duration.between(created, Instant.now()).getSeconds();
    }

    protected double incomingAnnounceFrequency() {
        return sampleFrequency(iaFreqDeque, IC_DEQUE_MIN_SAMPLE);
    }

    protected double outgoingAnnounceFrequency() {
        return sampleFrequency(oaFreqDeque, 1);
    }

    @Override
    public boolean OUT() {
        return OUT;
    }

    @Override
    public boolean IN() {
        return IN;
    }

    @Override
    public boolean FWD() {
        return FWD;
    }

    @Override
    public boolean RPT() {
        return RPT;
    }

    public String toString() {
        return getInterfaceName();
    }
}
