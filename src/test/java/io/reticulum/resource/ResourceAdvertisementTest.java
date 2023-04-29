package io.reticulum.resource;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

}