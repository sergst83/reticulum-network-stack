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

    private boolean enabled;

    @JsonAlias({"interface_mode", "mode"})
    private InterfaceMode interfaceMode = MODE_FULL;

    @JsonProperty("ifac_size")
    private Integer ifacSize;

    @JsonAlias({"networkname", "network_name"})
    private String ifacNetname;

    @JsonAlias({"passphrase", "pass_phrase"})
    private String ifacNetkey;

    @JsonProperty("bitrate")
    private Integer configuredBitrate;

    @JsonProperty("announce_rate_target")
    private Integer announceRateTarget;

    @JsonProperty("announce_rate_grace")
    private Integer announceRateGrace;

    @JsonProperty("announce_rate_penalty")
    private Integer announceRatePenalty;

    @JsonProperty("announce_cap")
    private float announceCap = ANNOUNCE_CAP / 100;

    public void setIfacSize(int newIfacSize) {
        if (newIfacSize >= IFAC_MIN_SIZE * 8) {
            ifacSize = newIfacSize / 8;
        }
    }

    public void setIfacNetname(String newIfacNetname) {
        if (StringUtils.isNotBlank(newIfacNetname)) {
            ifacNetname = newIfacNetname;
        }
    }

    public void setIfacNetkey(String newIfacNetkey) {
        if (StringUtils.isNotBlank(newIfacNetkey)) {
            ifacNetkey = newIfacNetkey;
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

    public void setAnnounceCap(float newAnnounceCap) {
        if (newAnnounceCap > 0 && newAnnounceCap < 100) {
            this.announceCap = newAnnounceCap / 100;
        }
    }
}
