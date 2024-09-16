package io.reticulum.utils;

import io.reticulum.identity.Identity;
import io.reticulum.interfaces.AbstractConnectionInterface;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

import static io.reticulum.constant.ReticulumConstant.IFAC_SALT;
import static io.reticulum.utils.IdentityUtils.fullHash;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

@UtilityClass
@Slf4j
public class InterfaceUtils {

    public static boolean initIFac(AbstractConnectionInterface iface) {
        if (isNotBlank(iface.getIfacNetName()) || isNotBlank(iface.getIfacNetKey())) {
            var ifacOrigin = new byte[]{};

            if (isNotBlank(iface.getIfacNetName())) {
                ifacOrigin = ArrayUtils.addAll(ifacOrigin, fullHash(iface.getIfacNetName().getBytes(UTF_8)));
            }

            if (isNotBlank(iface.getIfacNetKey())) {
                ifacOrigin = ArrayUtils.addAll(ifacOrigin, fullHash(iface.getIfacNetKey().getBytes(UTF_8)));
            }

            // TODO: 07.03.2023 проверить чтоб были хеши и ключи одинаковые с питоном
            //                  check that the hashes and keys are the same with Python
            var ifacOriginHash = fullHash(ifacOrigin);
            var hkdf = new HKDFBytesGenerator(new SHA256Digest());
            hkdf.init(new HKDFParameters(ifacOriginHash, IFAC_SALT, new byte[0]));
            var ifacKey = new byte[64];
            hkdf.generateBytes(ifacKey, 0, ifacKey.length);

            var identity = Identity.fromBytes(ifacKey);
            iface.setIfacKey(ifacKey);
            iface.setIdentity(identity);
            if (nonNull(identity)) {
                iface.setIfacSignature(identity.sign(fullHash(ifacKey)));
            } else {
                log.warn("Identity is null. Interface {} not initialised correctly!", iface);
                return false;
            }

            return true;
        }

        return true;
    }
}
