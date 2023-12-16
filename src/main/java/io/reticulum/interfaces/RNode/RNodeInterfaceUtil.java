package io.reticulum.interfaces.RNode;

public interface RNodeInterfaceUtil {
    int MAX_CHUNK = 32768;

    int FREQ_MIN = 137000000;
    int FREQ_MAX = 1020000000;

    int RSSI_OFFSET = 157;

    int CALLSIGN_MAX_LEN    = 32;

    int REQUIRED_FW_VER_MAJ = 1;
    int REQUIRED_FW_VER_MIN = 52;

    int RECONNECT_WAIT = 5;
}
