package io.reticulum.resource;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;

import static io.reticulum.constant.ResourceConstant.MAX_EFFICIENT_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceAdvertisementTest {

    @ParameterizedTest
    @ValueSource(strings = "8ba1740aa1640aa16e0aa168c4030000ffa172c4030000ffa16fc4030000ffa1690aa16c0aa171c4030000ffa1661fa16dc4030000ff")
    void unpack(String pythonHex) throws DecoderException {
        var adv = ResourceAdvertisement.unpack(Hex.decodeHex(pythonHex));
        System.out.println(Hex.encodeHexString(adv.pack(null)));
        assertEquals(10, adv.getT());
        assertEquals(10, adv.getD());
        assertEquals(10, adv.getN());
        assertEquals(10, adv.getI());
        assertEquals(10, adv.getL());
        assertEquals(31, adv.getF());
        assertEquals(255, new BigInteger(adv.getH()).intValue());
        assertEquals(255, new BigInteger(adv.getR()).intValue());
        assertEquals(255, new BigInteger(adv.getO()).intValue());
        assertEquals(255, new BigInteger(adv.getM()).intValue());
        assertTrue(adv.isC());
        assertTrue(adv.isE());
        assertTrue(adv.isS());
        assertTrue(adv.isU());
        assertTrue(adv.isP());
    }

    private static final String AT_BOUND =
            "8ba174ce002ffffda164ce002ffffda16e01a168c4200000000000000000000000000000"
            + "000000000000000000000000000000000000a172c40400000000a16fc420000000000000"
            + "0000000000000000000000000000000000000000000000000000a16901a16c01a171c410"
            + "11111111111111111111111111111111a16600a16dc40400000000";

    private static final String OVER_BOUND =
            "8ba174ce002ffffea164ce002ffffea16e01a168c4200000000000000000000000000000"
            + "000000000000000000000000000000000000a172c40400000000a16fc420000000000000"
            + "0000000000000000000000000000000000000000000000000000a16901a16c01a171c410"
            + "11111111111111111111111111111111a16600a16dc40400000000";

    /**
     * The reference rejects any advertisement whose transfer size exceeds
     * {@code MAX_EFFICIENT_SIZE * 3} (RNS/Resource.py:1374). Both vectors were
     * packed by Python RNS 1.5.2, which accepts the first and raises on the second.
     */
    @Test
    @DisplayName("Transfer size at the reference bound is accepted")
    void transferSizeAtBoundAccepted() throws DecoderException {
        var adv = ResourceAdvertisement.unpack(Hex.decodeHex(AT_BOUND));

        assertEquals(MAX_EFFICIENT_SIZE * 3, adv.getT());
    }

    @Test
    @DisplayName("Transfer size past the reference bound is rejected")
    void transferSizePastBoundRejected() throws DecoderException {
        var raw = Hex.decodeHex(OVER_BOUND);

        assertThrows(IllegalArgumentException.class, () -> ResourceAdvertisement.unpack(raw));
    }

    /**
     * Python packs {@code q: None} for any resource with no associated request —
     * that is, every ordinary transfer. Reading it used to throw
     * {@code MessageTypeCastException} and writing it threw on
     * {@code newBinary(null)}, so plain resource advertisements could be neither
     * received from nor sent to a reference peer.
     */
    @Test
    @DisplayName("Advertisement without a request ID round-trips")
    void nilRequestIdRoundTrips() throws DecoderException {
        var nilRequestId =
                "8ba174cd03e8a164cd03e8a16e01a168c420000000000000000000000000000000000000"
                + "0000000000000000000000000000a172c40400000000a16fc42000000000000000000000"
                + "00000000000000000000000000000000000000000000a16901a16c01a171c0a16600a16d"
                + "c40400000000";

        var adv = ResourceAdvertisement.unpack(Hex.decodeHex(nilRequestId));

        assertNull(adv.getQ(), "a nil request ID must read back as null");
        assertEquals(1000, adv.getT());

        // And it must pack again without throwing
        var repacked = ResourceAdvertisement.unpack(adv.pack(0));
        assertNull(repacked.getQ());
        assertEquals(1000, repacked.getT());
    }
}
