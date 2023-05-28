package io.reticulum;

import com.igormaznitsa.jbbp.utils.JBBPUtils;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.identity.Identity;
import io.reticulum.interfaces.HDLC;
import io.reticulum.packet.Packet;
import io.reticulum.packet.PacketContextType;
import io.reticulum.packet.PacketType;
import io.reticulum.utils.DestinationUtils;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.msgpack.core.MessagePack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

import static io.reticulum.utils.IdentityUtils.getRandomHash;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class UnstructuredTest implements HDLC {

    @Test
    void t() {
        var expected = "006415a41b";
        var i = 1679139867;
        System.out.println(Long.toBinaryString(i));
        var hash = getRandomHash();

        var array = intToByteArray(i);
        System.out.println(Arrays.toString(array));
        assertEquals(expected, Hex.encodeHexString(array));

//        assertEquals(expected, Hex.encodeHexString(BigInteger.valueOf(i).toByteArray()));
    }

    public static byte[] intToByteArray(long value) {
        var result = new byte[5];
        var valArray = BigInteger.valueOf(value).toByteArray();
        for (int i = 0; i < valArray.length && i < result.length; i++) {
            result[i] = valArray[i];
        }
        if (valArray.length < result.length) {
            ArrayUtils.shift(result, result.length - valArray.length);
        }

        return result;
    }

    @ParameterizedTest
    @CsvSource(value = {
            "9223372036854775807;cf7fffffffffffffff",
            "-9223372036854775808;d38000000000000000",
            "0;00",
            "-2147483648;d280000000",
            "2147483647;ce7fffffff",
            "-32768;d18000",
            "32767;cd7fff",
            "-128;d080",
            "127;7f"
    }, delimiterString = ";")
    void packLong(long l, String hex) throws IOException {
        var packer = MessagePack.newDefaultBufferPacker();
        packer.packLong(l);
        assertEquals(hex, Hex.encodeHexString(packer.toByteArray()));
        packer.close();
    }

    @Test
    void prepareDataToTransmitOverTcp() throws IOException {
        var identity = new Identity();
        var destination = new Destination();
        destination.setIdentity(identity);
        destination.setHash(DestinationUtils.hash(identity, "appName"));
        destination.setType(DestinationType.SINGLE);
        var packet = new Packet(destination, "test".getBytes(UTF_8), PacketType.ANNOUNCE, PacketContextType.NONE, null);
        packet.pack();

        var raw = packet.getRaw();

        var os = new ByteArrayOutputStream();
        os.write(FLAG);
        os.write(escapeHdlc(raw));
        os.write(FLAG);

        var data = os.toByteArray();
//        System.out.println(JBBPUtils.bin2str(os.toByteArray(), true));
        System.out.println(Arrays.toString(data));
        System.out.println(JBBPUtils.bin2str(data, true));
        System.out.println(JBBPUtils.bin2str(new byte[] {FLAG ^ ESC_MASK}));
        System.out.println(JBBPUtils.bin2str(new byte[] {ESC_MASK}));
        System.out.println(JBBPUtils.bin2str(new byte[] {FLAG}));
        System.out.println(JBBPUtils.bin2str(new byte[]{ESC, (byte) (FLAG ^ ESC_MASK)}, true));
        System.out.println(Integer.toBinaryString(ESC ^ ESC_MASK));
        System.out.println(Integer.toBinaryString(ESC));
        System.out.println(JBBPUtils.bin2str(new byte[] {ESC, (byte) (ESC ^ ESC_MASK)}, true));
    }
}
