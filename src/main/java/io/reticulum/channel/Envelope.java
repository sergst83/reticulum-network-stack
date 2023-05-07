package io.reticulum.channel;

import io.reticulum.packet.Packet;
import lombok.Data;

@Data
public class Envelope {
    private Packet packet;

    public int getSequence() {
        return 0;
    }

    public void setTracked(boolean tracked) {

    }
}
