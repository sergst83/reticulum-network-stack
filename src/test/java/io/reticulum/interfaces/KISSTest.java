package io.reticulum.interfaces;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

class KISSTest implements KISS {

    @Test
    void escape() {
        var os = new ByteArrayOutputStream();
        os.write(FESC);
        os.write(127);
        os.write(FEND);

        var data = os.toByteArray();

        Assertions.assertEquals("dbdd7fdbdc", Hex.encodeHexString(escapeKiss(data)));
    }
}