package io.reticulum.link;

import io.reticulum.interfaces.auto.AutoInterfaceConstant;
import io.reticulum.interfaces.backbone.BackboneServerInterface;
import io.reticulum.interfaces.tcp.TCPChannelInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.reticulum.constant.LinkConstant.MDU;
import static io.reticulum.constant.ReticulumConstant.HEADER_MAXSIZE;
import static io.reticulum.constant.ReticulumConstant.IFAC_MIN_SIZE;
import static io.reticulum.constant.ReticulumConstant.MTU;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins link MTU handling to Python RNS 1.5.2.
 * <p>
 * The link MTU is negotiated in the link request and confirmed in the proof;
 * {@code update_mdu} then derives the per-packet payload size from it
 * ({@code RNS/Link.py:512}). Java stored the negotiated MTU but never derived
 * anything from it, so a negotiated increase bought no extra payload.
 */
class LinkMtuTest {

    /** {@code floor((mtu - IFAC_MIN_SIZE - HEADER_MINSIZE - TOKEN_OVERHEAD)/16)*16 - 1} */
    @ParameterizedTest(name = "mtu {0} -> mdu {1}")
    @CsvSource({
            "500,     431",
            "1064,    991",
            "1196,    1119",
            "262144,  262063",
            "1048576, 1048495",
    })
    @DisplayName("MDU derived from the negotiated MTU matches the reference")
    void mduMatchesReference(int mtu, int expectedMdu) {
        assertEquals(expectedMdu, Link.mduForMtu(mtu), "mdu for mtu " + mtu);
    }

    @Test
    @DisplayName("The default MTU yields the static Link MDU")
    void defaultMtuMatchesStaticMdu() {
        assertEquals(MDU, Link.mduForMtu(MTU),
                "the static MDU must be exactly what the default MTU derives to");
        assertEquals(431, MDU);
    }

    /** {@code sdu = mtu - HEADER_MAXSIZE - IFAC_MIN_SIZE} (RNS/Resource.py:338) */
    @ParameterizedTest(name = "mtu {0} -> sdu {1}")
    @CsvSource({
            "500,     464",
            "1196,    1160",
            "262144,  262108",
            "1048576, 1048540",
    })
    @DisplayName("Resource SDU derived from the link MTU matches the reference")
    void sduMatchesReference(int mtu, int expectedSdu) {
        assertEquals(expectedSdu, mtu - HEADER_MAXSIZE - IFAC_MIN_SIZE);
    }

    /**
     * A negotiated MTU is only useful if the interface can actually receive a
     * frame that size. These were all 1064 locally while the reference used
     * larger values, so frames the reference sent were truncated (UDP) or
     * rejected as too long (framed TCP).
     */
    @Test
    @DisplayName("Interface hardware MTUs match the reference")
    void interfaceHardwareMtus() {
        assertEquals(1196, AutoInterfaceConstant.HW_MTU, "AutoInterface.HW_MTU, was 1064");
        assertEquals(262_144, TCPChannelInitializer.HW_MTU, "TCPInterface.HW_MTU, was 1064");
        assertEquals(1_048_576, BackboneServerInterface.HW_MTU, "BackboneInterface.HW_MTU");
    }

    @Test
    @DisplayName("A larger MTU yields a proportionally larger MDU")
    void largerMtuGivesLargerMdu() {
        assertTrue(Link.mduForMtu(AutoInterfaceConstant.HW_MTU) > Link.mduForMtu(MTU),
                "negotiating a larger MTU must increase the payload per packet");
    }
}
