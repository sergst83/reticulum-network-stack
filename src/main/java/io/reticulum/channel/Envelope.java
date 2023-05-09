package io.reticulum.channel;

import io.reticulum.packet.Packet;
import lombok.Data;

import java.util.HashMap;

@Data
public class Envelope {
    private Packet packet;

    public Envelope(LinkChannelOutlet outlet, byte[] raw) {

    }

    public int getSequence() {
        return 0;
    }

    public void setTracked(boolean tracked) {

    }

    public MessageBase unpack(HashMap<Integer, MessageBase> messageFactories) {
        return null;
    }
}
