package io.reticulum.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.reticulum.interfaces.InterfaceMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.reticulum.interfaces.InterfaceMode.MODE_GATEWAY;
import static io.reticulum.interfaces.InterfaceMode.MODE_INTERNAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code [reticulum]} configuration surface.
 * <p>
 * Only the section itself is deserialised here, not a whole config file — the
 * interfaces section drags Jackson through interface classes and trips the
 * JPMS issue that already breaks {@code ReticulumTest.testConfigYamlParse}.
 */
class ReticulumConfTest {

    private static ReticulumConf parse(String yaml) throws Exception {
        return new ObjectMapper(new YAMLFactory()).readValue(yaml, ReticulumConf.class);
    }

    @Test
    @DisplayName("An empty section leaves every option unset")
    void emptySectionLeavesOptionsUnset() throws Exception {
        var conf = parse("{}");

        assertNull(conf.getDiscoverInterfaces());
        assertNull(conf.getAutoconnectDiscoveredInterfaces());
        assertNull(conf.getAutoconnectInterfaceMode());
        assertNull(conf.getRequiredDiscoveryValue());
        assertNull(conf.getInterfaceDiscoverySources());
        assertNull(conf.getInstanceName());
        assertNull(conf.getLinkMtuDiscovery());
        assertNull(conf.getDefaultArTarget());
        assertNull(conf.getIcBurstFreq());
    }

    @Test
    @DisplayName("Discovery options are read")
    void discoveryOptions() throws Exception {
        var conf = parse(String.join("\n",
                "discover_interfaces: true",
                "required_discovery_value: 18",
                "autoconnect_discovered_interfaces: 4",
                "autoconnect_interface_mode: gateway",
                "autoconnect_announces_to_internal: true",
                "interface_discovery_sources:",
                "  - c3493380191234476fef6a6ee4884554"));

        assertEquals(true, conf.getDiscoverInterfaces());
        assertEquals(18, conf.getRequiredDiscoveryValue());
        assertEquals(4, conf.getAutoconnectDiscoveredInterfaces());
        assertEquals("gateway", conf.getAutoconnectInterfaceMode());
        assertEquals(true, conf.getAutoconnectAnnouncesToInternal());
        assertEquals(1, conf.getInterfaceDiscoverySources().size());
        assertEquals("c3493380191234476fef6a6ee4884554", conf.getInterfaceDiscoverySources().get(0));
    }

    @Test
    @DisplayName("Announce rate and ingress control defaults are read")
    void rateAndIngressDefaults() throws Exception {
        var conf = parse(String.join("\n",
                "default_ar_target: 3600",
                "default_ar_penalty: 0",
                "default_ar_grace: 5",
                "ic_max_held_announces: 128",
                "ic_burst_hold: 15.0",
                "ic_burst_freq_new: 3.0",
                "ic_burst_freq: 10.0",
                "ic_new_time: 7200",
                "ic_burst_penalty: 15",
                "ic_held_release_interval: 5"));

        assertEquals(3600, conf.getDefaultArTarget());
        assertEquals(0, conf.getDefaultArPenalty());
        assertEquals(5, conf.getDefaultArGrace());
        assertEquals(128, conf.getIcMaxHeldAnnounces());
        assertEquals(15.0, conf.getIcBurstHold());
        assertEquals(3.0, conf.getIcBurstFreqNew());
        assertEquals(10.0, conf.getIcBurstFreq());
        assertEquals(7200L, conf.getIcNewTime());
        assertEquals(15L, conf.getIcBurstPenalty());
        assertEquals(5L, conf.getIcHeldReleaseInterval());
    }

    @Test
    @DisplayName("Pre-existing options still parse")
    void existingOptions() throws Exception {
        var conf = parse(String.join("\n",
                "enable_transport: true",
                "share_instance: false",
                "shared_instance_port: 37428",
                "instance_control_port: 37429",
                "panic_on_interface_error: true",
                "use_implicit_proof: false",
                "respond_to_probes: true",
                "link_mtu_discovery: false",
                "instance_name: qortal"));

        assertEquals(true, conf.getEnableTransport());
        assertEquals(false, conf.getShareInstance());
        assertEquals(37428, conf.getSharedInstancePort());
        assertEquals(37429, conf.getInstanceControlPort());
        assertEquals(true, conf.getPanicOnInterfaceError());
        assertEquals(false, conf.getUseImplicitProof());
        assertEquals(true, conf.getRespondToProbes());
        assertEquals(false, conf.getLinkMtuDiscovery());
        assertEquals("qortal", conf.getInstanceName());
    }

    /**
     * The autoconnect mode is stored as a string and resolved through
     * {@link InterfaceMode#parseName}, which is what the reference's alias table
     * amounts to.
     */
    @Test
    @DisplayName("Interface mode names resolve, including the aliases")
    void interfaceModeNames() {
        assertEquals(MODE_GATEWAY, InterfaceMode.parseName("gateway"));
        assertEquals(MODE_GATEWAY, InterfaceMode.parseName("gw"));
        assertEquals(MODE_INTERNAL, InterfaceMode.parseName("internal"));
        assertEquals(InterfaceMode.MODE_ACCESS_POINT, InterfaceMode.parseName("ap"));
        assertEquals(InterfaceMode.MODE_ACCESS_POINT, InterfaceMode.parseName("access_point"));
        assertEquals(InterfaceMode.MODE_POINT_TO_POINT, InterfaceMode.parseName("ptp"));
        assertEquals(InterfaceMode.MODE_FULL, InterfaceMode.parseName("full"));
        assertEquals(InterfaceMode.MODE_ROAMING, InterfaceMode.parseName("roaming"));
        assertEquals(InterfaceMode.MODE_BOUNDARY, InterfaceMode.parseName("boundary"));

        assertThrows(Exception.class, () -> InterfaceMode.parseName("not-a-mode"));
    }

    /**
     * Auto-connection is off unless configured. Previously
     * {@code InterfaceDiscovery.shouldAutoconnect()} returned true
     * unconditionally, so a node dialled out to interfaces it found on the
     * network without being asked.
     */
    @Test
    @DisplayName("Auto-connection is opt-in")
    void autoconnectIsOptIn() throws Exception {
        assertNull(parse("{}").getAutoconnectDiscoveredInterfaces(),
                "absent means disabled");
        assertEquals(0, parse("autoconnect_discovered_interfaces: 0").getAutoconnectDiscoveredInterfaces(),
                "zero means disabled");
        assertTrue(parse("autoconnect_discovered_interfaces: 4").getAutoconnectDiscoveredInterfaces() > 0);
    }
}
