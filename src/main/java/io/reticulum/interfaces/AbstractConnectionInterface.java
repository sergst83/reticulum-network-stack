package io.reticulum.interfaces;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.commons.lang3.StringUtils;

import static io.reticulum.interfaces.InterfaceMode.MODE_FULL;
import static io.reticulum.utils.ReticulumConstant.ANNOUNCE_CAP;
import static io.reticulum.utils.ReticulumConstant.IFAC_MIN_SIZE;
import static io.reticulum.utils.ReticulumConstant.MINIMUM_BITRATE;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Getter
@Setter
@SuperBuilder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class AbstractConnectionInterface implements ConnectionInterface {

    protected boolean enabled;

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
}
