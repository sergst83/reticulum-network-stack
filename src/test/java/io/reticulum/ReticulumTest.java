package io.reticulum;

import io.reticulum.vendor.config.ConfigObj;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static io.reticulum.constant.ReticulumConstant.ETC_DIR;
import static io.reticulum.constant.ReticulumConstant.IFAC_SALT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.codec.digest.DigestUtils.getSha256Digest;
import static org.apache.commons.lang3.SystemUtils.USER_HOME;

class ReticulumTest {

    @Test
    void t() throws DecoderException {
        System.out.println(Arrays.toString(Hex.decodeHex("adf54d882c9a9b80771eb4995d702d4a3e733391b2a0f53f416d9f907e55cff8")));
        System.out.println(2 + 1 + (128 / 8) * 2);
    }

    @Test
    void path() {
        System.out.println(initConfig(null));
    }


    @Test
    void testConfigYamlParse() throws IOException {
        var config = ConfigObj.initConfig(Path.of(getClass().getClassLoader().getResource("reticulum.default.yml").getPath()));

        System.out.println(config);
    }

    @Test
    void testHKDF() {
        var ifac_netname = "name";
        var ifac_netkey = "password";
        var ifacOrigin = new byte[]{};
        ifacOrigin = ArrayUtils.addAll(ifacOrigin, getSha256Digest().digest(ifac_netname.getBytes(UTF_8)));
        ifacOrigin = ArrayUtils.addAll(ifacOrigin, getSha256Digest().digest(ifac_netkey.getBytes(UTF_8)));

        var ifacOriginHash = getSha256Digest().digest(ifacOrigin);

        var HKDF = new HKDFBytesGenerator(new SHA256Digest());
        HKDF.init(new HKDFParameters(ifacOriginHash, IFAC_SALT, new byte[0]));
        var result = new byte[64];
        var len = HKDF.generateBytes(result, 0, result.length);

        System.out.println(Hex.encodeHexString(result));
    }

    private String initConfig(String configDir) {
        if (StringUtils.isNotBlank(configDir)) {
            return configDir;
        } else {
            if (Files.isDirectory(Path.of(ETC_DIR)) && Files.exists(Path.of(ETC_DIR, "config"))) {
                return ETC_DIR;
            } else if (
                    Files.isDirectory(Path.of(USER_HOME, ".config", "reticulum"))
                            && Files.exists(Path.of(USER_HOME, ".config", "reticulum", "config"))
            ) {
                return Path.of(USER_HOME, ".config", "reticulum").toString();
            } else {
                return Path.of(USER_HOME, ".reticulum").toString();
            }
        }
    }
}