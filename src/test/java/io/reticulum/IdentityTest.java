package io.reticulum;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.reticulum.constant.ReticulumConstant.IFAC_SALT;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static io.reticulum.utils.IdentityUtils.fullHash;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO: 12.03.2023 сделать тест, проверить что с питоном совпадает
class IdentityTest {

    @Test
    void sign() {
    }

    @Test
    void signAndValidateIfaceKey() throws IOException {
        var netName = "name";
        var netKey = "passfrase";

        var ifacOriginHash = fullHash(concatArrays(fullHash(netName.getBytes(UTF_8)), fullHash(netKey.getBytes(UTF_8))));

        var hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(ifacOriginHash, IFAC_SALT, new byte[0]));
        var ifacKey = new byte[64];
        hkdf.generateBytes(ifacKey, 0, ifacKey.length);

        var identity = Identity.fromBytes(ifacKey);

        var message = fullHash(ifacKey);
        var signature = identity.sign(message);
        var actual = Hex.encodeHexString(signature);
        var expected = "f719fae770705c43b6047fd919fc89b4f4a8655477ef7ee13cb14121011afc571d049990a71a04457d8600e60afdca93f96de02216ad52a32eff23e82807840e";

        assertEquals(expected, actual);

        assertTrue(identity.validate(signature, message));
    }

    @Test
    void validate() {
    }

    @Test
    void encryptAndDecrypt() throws DecoderException {
        var identity1 = new Identity(false);
        var identity2 = new Identity(false);
        identity2.loadPublicKey(identity1.getPublicKey());

        var token = identity2.encrypt("passfrase".getBytes(UTF_8));

        assertEquals(
                Hex.decodeHex("57f6a3f2f8039b224ee2094b4aaefceb1556f4b50a6a0dc1b8b20f8f3cd8bd682f58f6229b50075d7c103bb68938bdca4a4e38d0260f50bb6743db749348c369cff71f63899fdbb7bca5f94e8e897de25816986f4c8f8f4216b95e4b831fd1de").length,
                token.length
                );

        System.out.println(Hex.encodeHexString(token));

        var text = identity1.decrypt(token);

        assertEquals("passfrase", new String(text));
    }

    @Test
    void decrypt() throws DecoderException {
        var privateKey = "80d455d6a5715f93a503c0f2b9c56aff7a94c2524ff8bb0042a5ca7fb3196c7800064603a3559dd3668f7dc900d7b9e15432cf830a28d45078c9335d26b5423c";
        var encrypted = "a88574e1facaa5693d2d71522d07c2105da6470b636d12bee608900c6465617e77e22257d0a4096ba1ca5c415e631de4884d6f6f9d3512694d4e823dbfa5c7d0e6995e946d0de7b5b40f049a1b118f4bf529aa0a6f23a0019a7cc0aa18882de4";

        var id1 = Identity.fromBytes(Hex.decodeHex(privateKey));
        var decypted = id1.decrypt(Hex.decodeHex(encrypted));

        assertEquals("passfrase", new String(decypted));
    }
}