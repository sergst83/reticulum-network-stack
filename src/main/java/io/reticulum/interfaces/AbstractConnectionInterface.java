package io.reticulum.interfaces;

import com.fasterxml.jackson.annotation.JsonAlias;
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
public abstract class AbstractConnectionInterface extends Thread implements ConnectionInterface {

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
    protected boolean enabled;
    protected byte[] ifacKey;
    protected byte[] ifacSignature;
    protected final Instant created = Instant.now();

    protected List<Instant> oaFreqDeque = new CopyOnWriteArrayList<>();
    protected List<Instant> iaFreqDeque = new CopyOnWriteArrayList<>();

    @JsonAlias({"interface_mode", "mode"})
    protected InterfaceMode interfaceMode = MODE_FULL;

    @JsonProperty("ifac_size")
    protected Integer ifacSize;

    @JsonAlias({"networkname", "network_name"})
    protected String ifacNetName;

    @JsonAlias({"passphrase", "pass_phrase"})
    protected String ifacNetKey;

    @JsonProperty("bitrate")
    protected Integer bitrate;

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
    protected Double icBurstHold = 60.0;

    @JsonProperty("ic_burst_freq_new")
    protected Double icBurstFreqNew = 3.5;

    @JsonProperty("ic_burst_freq")
    protected Double icBurstFreq = 12.0;

    @JsonProperty("ic_new_time")
    protected long icNewTime = 2 * 60 * 60; //seconds

    @JsonProperty("ic_burst_penalty")
    protected long icBurstPenalty = 5 * 60; //seconds

    @JsonProperty("ic_held_release_interval")
    protected long icHeldReleaseInterval = 30; //seconds

    @JsonProperty("announce_cap")
    protected Double announceCap = ANNOUNCE_CAP / 100;
    protected Instant announceAllowedAt;
    protected Queue<AnnounceQueueEntry> announceQueue = new ConcurrentLinkedQueue<>();
    protected Map<String, Packet> heldAnnounces = new ConcurrentHashMap<>();

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
                        Executors
                                .newSingleThreadScheduledExecutor()
                                .scheduleAtFixedRate(this::processAnnounceQueue, 1, waitTime, TimeUnit.SECONDS);
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
        oaFreqDeque.add(0, Instant.now());
        if (nonNull(getParentInterface())) {
            getParentInterface().sentAnnounce(true);
        }
    }

    @Override
    public void receivedAnnounce(boolean fromSpawned) {
        iaFreqDeque.add(0, Instant.now());
        if (nonNull(getParentInterface())) {
            getParentInterface().receivedAnnounce(true);
        }
    }

    @Override
    public boolean shouldIngressLimit() {
        return false;
    }

    @Override
    public void holdAnnounce(Packet announcePacket) {
        var hash = Hex.encodeHexString(announcePacket.getDestinationHash());
        if (heldAnnounces.containsKey(hash)) {
            heldAnnounces.put(hash, announcePacket);
        } else if (MapUtils.size(heldAnnounces) >= icMaxHeldAnnounces) {
            heldAnnounces.put(hash, announcePacket);
        }
    }

    @Override
    public void processHeldAnnounces() {
        try {
            if (isFalse(shouldIngressLimit()) && MapUtils.size(heldAnnounces) > 0 && Instant.now().isAfter(icHeldRelease.get())) {
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
     * Age of interface
     *
     * @return seconds
     */
    protected long age() {
        return Duration.between(Instant.now(), created).getSeconds();
    }

    protected double incomingAnnounceFrequency() {
        if (isFalse(iaFreqDeque.size() > 1)) {
            return 0;
        } else {
            var dqLen = iaFreqDeque.size();
            var deltaSum = 0L;
            for (int i = 1; i < dqLen; i++) {
                deltaSum += Duration.between(iaFreqDeque.get(i), iaFreqDeque.get(i - 1)).getSeconds();
            }
            deltaSum += Duration.between(Instant.now(), oaFreqDeque.get(dqLen - 1)).getSeconds();

            return deltaSum == 0 ? 0 : (double) 1 / deltaSum / dqLen;
        }
    }

    protected double outgoingAnnounceFrequency() {
        if (isFalse(oaFreqDeque.size() > 1)) {
            return 0;
        } else {
            var dqLen = oaFreqDeque.size();
            var deltaSum = 0L;
            for (int i = 1; i < dqLen; i++) {
                deltaSum += Duration.between(oaFreqDeque.get(i), oaFreqDeque.get(i - 1)).getSeconds();
            }
            deltaSum += Duration.between(Instant.now(), oaFreqDeque.get(dqLen - 1)).getSeconds();

            return deltaSum == 0 ? 0 : (double) 1 / deltaSum / dqLen;
        }
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
