package io.reticulum.channel;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

@Slf4j
public class Channel {
    private final LinkChannelOutlet outlet;
    private final LinkedList<Envelope> txRing;
    private final LinkedList<Envelope> rxRing;
    private final List<MessageCallbackType> messageCallbacks;
    private final long nextSequence;
    private final HashMap<Integer, MessageBase> messageFactories;
    private final int maxTries;

    public Channel(final LinkChannelOutlet linkChannelOutlet) {
        this.outlet = linkChannelOutlet;
        this.txRing = new LinkedList<>();
        this.rxRing = new LinkedList<>();
        this.messageCallbacks = new ArrayList<>();
        this.nextSequence = 0L;
        this.messageFactories = new HashMap<>();
        this.maxTries = 5;
    }

    public synchronized void shutdown() {
        messageCallbacks.clear();
        clearRings();
    }

    private void clearRings() {
        for (Envelope envelope : txRing) {
            if (nonNull(envelope.getPacket())) {
                outlet.setPacketTimeoutCallback(envelope.getPacket(), null, null);
                outlet.setPacketDeliveredCallback(envelope.getPacket(), null);
            }
            txRing.clear();
            rxRing.clear();
        }
    }

    private synchronized boolean emplaceEnvelope(@NonNull final Envelope envelope, @NonNull final LinkedList<Envelope> ring) {
        var i = 0;
        for (Envelope existing : ring) {
            if (
                    existing.getSequence() > envelope.getSequence()
                    && isFalse(existing.getSequence() / 2 > envelope.getSequence()) //account for overflow
            ) {
                ring.set(i, envelope);

                return true;
            }

            if (existing.getSequence() == envelope.getSequence()) {
                log.trace("Envelope: Emplacement of duplicate envelope sequence");

                return false;
            }
            i++;
        }
        envelope.setTracked(true);
        ring.add(envelope);

        return true;
    }

    public void receive(byte[] plaintext) {

    }
}
