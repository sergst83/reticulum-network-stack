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
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ArrayUtils;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.msgpack.core.MessagePack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

import static io.reticulum.utils.IdentityUtils.concatArrays;
import static io.reticulum.utils.IdentityUtils.getRandomHash;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.ArrayUtils.getLength;
import static org.apache.commons.lang3.ArrayUtils.subarray;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void ifacInbound() throws DecoderException {
//        var message = "b7b6b34217e05fd8a644424c7d5ed5aa282308a2084c1a5a3ce99a166334ac54e90205cfe4298991a4f097d247d3804f508fb0a976387832d7aef6d60949d32a72a3c04b338294e6d1260f932f0e7ff3245a24efdfadbc3e093fc4dfef1d4e5f4a600a6b13dfa4d007699eadb0527d5e7a0c0d464d506d53d435131ace42a2d09d58aaa7dc01e189f60438e1bc66bb2329829c7082140d00ece6e2955d743329c3b2a06baa1790d13810579b5b2354ebce8184dc29c7e3d2ef";
        var message = "e10a2a904f3c8f886ea22e5eb7b267e8130f0868d1d0ac3c0e62bd6c0d37a16273b39b943cd19ceb947b93c9571030a906a2cbf059eec2a54dbc9bc32bd41c671d29a612f6940f6874a956bdadd016aa25df6ceab68352491c37472750846263141b8b32f224ddd79634da49603cd8e8254dd547fb461a44aa89b1164afaf0d8cf93aae7e87211a72a028a64801d2df5ca578531d07229a2facbe3b46d134cf2a68462d182a0524878c7c1d816480ef8d0cf38a9bbe5bd";
        var ifacKey = "c83d7db01fa730897bddbc660c316c45f80b3f00093736f7ac74a76585d77255ff5a451ff6b19ba613f745b2b53b4594f5b8194eb9b94afda540454c2cd78748";
        var ifacSize = 16;
        var localRaw = new byte[] {};

        var result = ifacAuthority(Hex.decodeHex(message), Hex.decodeHex(ifacKey), ifacSize, localRaw);

        assertTrue(result);
    }

    private boolean ifacAuthority(final byte[] raw, final byte[] ifacKey, final int ifacSize, byte[] localRaw) {
        var identity = Identity.fromBytes(ifacKey);
        System.out.println(identity.getHexHash());
        //If interface access codes are enabled, we must authenticate each packet.
        if (getLength(raw) > 2) {
            if (nonNull(identity)) {
                //Check that IFAC flag is set
                if ((raw[0] & 0x80) == 0x80) {
                    if (getLength(raw) > 2 + ifacSize) {
                        //Extract IFAC
                        var ifac = subarray(raw, 2, 2 + ifacSize);

                        //Generate mask
                        var hkdf = new HKDFBytesGenerator(new SHA256Digest());
                        hkdf.init(new HKDFParameters(ifac, ifacKey, new byte[0]));
                        var mask = new byte[getLength(raw)];
                        hkdf.generateBytes(mask, 0, mask.length);
                        System.out.println("mask: " + Hex.encodeHexString(mask));

                        //Unmask payload
                        var i = 0;
                        var unmaskedRaw = new byte[0];
                        for (byte b : raw) {
                            if (i <= 1 || i > ifacSize + 1) {
                                //Unmask header bytes and payload
                                unmaskedRaw = ArrayUtils.add(unmaskedRaw, (byte) (b ^ mask[i]));
                            } else {
                                //Don't unmask IFAC itself
                                unmaskedRaw = ArrayUtils.add(unmaskedRaw, b);
                            }
                            i++;
                        }

                        System.out.println("unmasked_raw: " + Hex.encodeHexString(unmaskedRaw));

                        //Unset IFAC flag
                        var newHeader = new byte[]{(byte) (unmaskedRaw[0] & 0x7f), unmaskedRaw[1]};

                        //Re-assemble packet
                        var newRaw = concatArrays(newHeader, subarray(unmaskedRaw, 2 + ifacSize, unmaskedRaw.length));

                        System.out.println("new_row: " + Hex.encodeHexString(newRaw));

                        //Calculate expected IFAC
                        var signed = identity.sign(newRaw);
                        System.out.println("signed: " + Hex.encodeHexString(signed));
                        var expectedIfac = subarray(signed, signed.length - ifacSize, signed.length);

                        //Check it
                        if (Arrays.equals(ifac, expectedIfac)) {
                            localRaw = newRaw;
                            return true;
                        } else {
                            return false;
                        }
                    } else {
                        return false;
                    }
                } else {
                    //If the IFAC flag is not set, but should be, drop the packet.
                    return false;
                }
            } else {
                //If the interface does not have IFAC enabled, check the received packet IFAC flag.
                if ((raw[0] & 0x80) == 0x80) {
                    //If the flag is set, drop the packet
                    return false;
                }

                localRaw = raw;
                return true;
            }
        } else {
            return false;
        }
    };

    @Test
    void compare() throws DecoderException {
        var a = "0100f957939a4138ac0974a261bcaa2aa69d004e016685d96c096c54a11433b1a7db0a25fbeb3fd4b5b4e138236637a1ee384b0dc2388fbf13af2b896fd210088ed59d4d169c499b708fbc57f7fc48f5f37a412efa4a32514efa3bac18f298e193230065c8e1a2cca9cb94398075e804dd28b029c54517b3bc5958ced9ca8d84a5408961e971c3e2cad991f2a79f3879e89009ca756e7016c6402e4e66623f4d33b56e7902440d";
        var b = "010af957939a4138ac0974a261bcaa2aa69d004e016685d96c096c54a11433b1a7db0a25fbeb3fd4b5b4e138236637a1ee384b0dc2388fbf13af2b896fd210088ed59d4d169c499b708fbc57f7fc48f5f37a412efa4a32514efa3bac18f298e193230065c8e1a2cca9cb94398075e804dd28b029c54517b3bc5958ced9ca8d84a5408961e971c3e2cad991f2a79f3879e89009ca756e7016c6402e4e66623f4d33b56e7902440d";

        System.out.println(JBBPUtils.bin2str(Hex.decodeHex(a), true));
        System.out.println(JBBPUtils.bin2str(Hex.decodeHex(b), true));
    }
}
