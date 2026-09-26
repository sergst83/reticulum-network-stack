package io.reticulum.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ReticulumConf {

    @JsonProperty("share_instance")
    private Boolean shareInstance;

    @JsonProperty("shared_instance_port")
    private Integer sharedInstancePort;

    @JsonProperty("instance_control_port")
    private Integer instanceControlPort;

    @JsonProperty("enable_transport")
    private Boolean enableTransport;

    @JsonProperty("panic_on_interface_error")
    private Boolean panicOnInterfaceError;

    @JsonProperty("use_implicit_proof")
    private Boolean useImplicitProof;

    @JsonProperty("respond_to_probes")
    private Boolean respondToProbes;

    @JsonProperty("force_shared_instance_bitrate")
    private Integer forceSharedInstanceBitrate;

    /**
     * Whether links may negotiate an MTU larger than the Reticulum default when
     * the next-hop interface supports it. Defaults to true, as in the reference.
     */
    @JsonProperty("link_mtu_discovery")
    private Boolean linkMtuDiscovery;

    /**
     * Whether auto-connected discovered interfaces permit their announces to be
     * propagated into internal-mode interfaces.
     */
    @JsonProperty("autoconnect_announces_to_internal")
    private Boolean autoconnectAnnouncesToInternal;

    // ── Interface discovery ──────────────────────────────────────────────────

    /** Whether to look for interfaces announced on the network. Default false. */
    @JsonProperty("discover_interfaces")
    private Boolean discoverInterfaces;

    /** Minimum stamp value a discovered interface must carry to be remembered. */
    @JsonProperty("required_discovery_value")
    private Integer requiredDiscoveryValue;

    /**
     * How many discovered interfaces may be auto-connected. Zero or absent
     * disables auto-connection entirely, as in the reference.
     */
    @JsonProperty("autoconnect_discovered_interfaces")
    private Integer autoconnectDiscoveredInterfaces;

    /** Interface mode assigned to auto-connected discovered interfaces. */
    @JsonProperty("autoconnect_interface_mode")
    private String autoconnectInterfaceMode;

    /** Identity hashes (hex) from which interface discoveries are accepted. */
    @JsonProperty("interface_discovery_sources")
    private List<String> interfaceDiscoverySources;

    // ── Instance ─────────────────────────────────────────────────────────────

    /** Name distinguishing this instance when several run on one system. */
    @JsonProperty("instance_name")
    private String instanceName;

    // ── Announce rate defaults, applied to interfaces that do not set them ───

    @JsonProperty("default_ar_target")
    private Integer defaultArTarget;

    @JsonProperty("default_ar_penalty")
    private Integer defaultArPenalty;

    @JsonProperty("default_ar_grace")
    private Integer defaultArGrace;

    // ── Ingress control defaults, applied likewise ───────────────────────────

    @JsonProperty("ic_max_held_announces")
    private Integer icMaxHeldAnnounces;

    @JsonProperty("ic_burst_hold")
    private Double icBurstHold;

    @JsonProperty("ic_burst_freq_new")
    private Double icBurstFreqNew;

    @JsonProperty("ic_burst_freq")
    private Double icBurstFreq;

    @JsonProperty("ic_new_time")
    private Long icNewTime;

    @JsonProperty("ic_burst_penalty")
    private Long icBurstPenalty;

    @JsonProperty("ic_held_release_interval")
    private Long icHeldReleaseInterval;

    // ── Path request ingress/egress control defaults ─────────────────────────

    @JsonProperty("ic_pr_burst_freq_new")
    private Double icPrBurstFreqNew;

    @JsonProperty("ic_pr_burst_freq")
    private Double icPrBurstFreq;

    /** Whether outgoing path requests are rate limited. Off by default. */
    @JsonProperty("egress_control")
    private Boolean egressControl;

    @JsonProperty("ec_pr_freq")
    private Double ecPrFreq;

    /** Gravity applied to interfaces that do not configure their own. */
    @JsonProperty("default_gravity")
    private Integer defaultGravity;

    /** Gravity applied to auto-connected discovered interfaces. */
    @JsonProperty("autoconnect_interface_gravity")
    private Integer autoconnectInterfaceGravity;
}
