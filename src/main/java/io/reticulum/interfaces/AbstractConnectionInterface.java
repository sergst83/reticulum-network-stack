package io.reticulum.interfaces;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.reticulum.identity.Identity;
import io.reticulum.Transport;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.reticulum.interfaces.InterfaceMode.MODE_ACCESS_POINT;
import static io.reticulum.interfaces.InterfaceMode.MODE_FULL;
import static io.reticulum.interfaces.InterfaceMode.MODE_GATEWAY;
import static io.reticulum.constant.ReticulumConstant.ANNOUNCE_CAP;
import static io.reticulum.constant.ReticulumConstant.IFAC_MIN_SIZE;
import static io.reticulum.constant.ReticulumConstant.MINIMUM_BITRATE;
import static java.math.BigInteger.ZERO;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public abstract class AbstractConnectionInterface extends Thread implements ConnectionInterface {

    protected boolean IN = false;

    @JsonProperty("outgoing")
    protected boolean OUT = false;
    protected boolean FWD = false;
    protected boolean RPT = false;
    protected AtomicBoolean online = new AtomicBoolean(false);
    protected String interfaceName;
    protected int bitrate;
    protected AtomicReference<BigInteger> rxb = new AtomicReference<>(ZERO);
    protected AtomicReference<BigInteger> txb = new AtomicReference<>(ZERO);
    protected static final InterfaceMode[] DISCOVER_PATHS_FOR = new InterfaceMode[]{MODE_ACCESS_POINT, MODE_GATEWAY};

    protected Transport transport;
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
    protected Integer configuredBitrate;

    @JsonProperty("announce_rate_target")
    protected Integer announceRateTarget;

    @JsonProperty("announce_rate_grace")
    protected Integer announceRateGrace;

    @JsonProperty("announce_rate_penalty")
    protected Integer announceRatePenalty;

    @JsonProperty("announce_cap")
    protected double announceCap = ANNOUNCE_CAP / 100;
    protected Instant announceAllowedAt;
    protected Queue<?> announceQueue = new LinkedList<>();

    public void setIfacSize(int newIfacSize) {
        if (newIfacSize >= IFAC_MIN_SIZE * 8) {
            ifacSize = newIfacSize / 8;
        }
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

    public void setConfiguredBitrate(int bitrate) {
        if (bitrate >= MINIMUM_BITRATE) {
            configuredBitrate = bitrate;
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
}
