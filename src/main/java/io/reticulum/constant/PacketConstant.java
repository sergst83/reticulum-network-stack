package io.reticulum.constant;

import static io.reticulum.constant.IdentityConstant.HASHLENGTH;
import static io.reticulum.constant.IdentityConstant.SIGLENGTH;

public class PacketConstant {
    /**
     * in seconds
     */
    public static final int TIMEOUT_PER_HOP = ReticulumConstant.DEFAULT_PER_HOP_TIMEOUT;

    public static final int EXPL_LENGTH = HASHLENGTH / 8 + SIGLENGTH / 8;
    public static final int IMPL_LENGTH = SIGLENGTH / 8;
}
