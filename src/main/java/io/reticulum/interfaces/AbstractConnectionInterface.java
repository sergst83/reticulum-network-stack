package io.reticulum.interfaces;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.reticulum.identity.Identity;
import io.reticulum.transport.AnnounceQueueEntry;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.reticulum.constant.ReticulumConstant.ANNOUNCE_CAP;
import static io.reticulum.constant.ReticulumConstant.IFAC_MIN_SIZE;
import static io.reticulum.constant.ReticulumConstant.MINIMUM_BITRATE;
import static io.reticulum.constant.ReticulumConstant.QUEUED_ANNOUNCE_LIFE;
import static io.reticulum.interfaces.InterfaceMode.MODE_FULL;
import static java.math.BigInteger.ZERO;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.stream.Collectors.toList;

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

    protected Identity identity;
    protected boolean enabled;
    protected byte[] ifacKey;
    protected byte[] ifacSignature;

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

    @JsonProperty("announce_cap")
    protected Double announceCap = ANNOUNCE_CAP / 100;
    protected Instant announceAllowedAt;
    protected Queue<AnnounceQueueEntry> announceQueue = new LinkedList<>();

    public void setIfacSize(int newIfacSize) {
        ifacSize = newIfacSize;
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

    public void setBitrate(int bitrate) {
        if (bitrate >= MINIMUM_BITRATE) {
            this.bitrate = bitrate;
        }
    }

    public void setAnnounceRateTarget(int newAnnounceRateTarget) {
        if (newAnnounceRateTarget > 0) {
            announceRateTarget = newAnnounceRateTarget;
        }
    }

    public void setAnnounceRateGrace(int newAnnounceRateGrace) {
        if (newAnnounceRateGrace > 0) {
            this.announceRateGrace = newAnnounceRateGrace;
        }
    }

    public void setAnnounceRatePenalty(int newAnnounceRatePenalty) {
        if (newAnnounceRatePenalty > 0) {
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

                if (announceQueue.size() > 0) {
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

                    if (announceQueue.size() > 0) {
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
