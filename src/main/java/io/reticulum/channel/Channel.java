package io.reticulum.channel;

import io.reticulum.link.Link;
import io.reticulum.message.MessageBase;
import io.reticulum.message.MessageType;
import io.reticulum.packet.Packet;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Function;

import static io.reticulum.channel.MessageState.MSGSTATE_DELIVERED;
import static io.reticulum.channel.MessageState.MSGSTATE_SENT;
//import static io.reticulum.utils.IdentityUtils.truncatedHash;
import static java.util.Comparator.comparingInt;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.concurrent.Executors.defaultThreadFactory;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.ArrayUtils.getLength;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

/**
 * Provides reliable delivery of messages over a link.
 * <br/>
 * <br/>
 * {@link Channel} differs from {@link io.reticulum.destination.Request} and {@link io.reticulum.resource.Resource}
 * in some important ways:
 * <br/>
 * <strong>Continuous</strong>:
 * Messages can be sent or received as long as {@link io.reticulum.link.Link} is open.
 * <br/>
 * <strong>Bi-directional</strong>:
 * Messages can be sent in either direction on {@link io.reticulum.link.Link} neither end is the client or server.
 * <br/>
 * <strong>Size-constrained</strong>:
 * Messages must be encoded into a single packet.
 * <br/>
 * <br/>
 * {@link Channel} is similar to {@link Packet}, except that it
 * provides reliable delivery (automatic retries) as well
 * as a structure for exchanging several types of
 * messages over the {@link io.reticulum.link.Link}
 * <br/>
 * {@link Channel} is not instantiated directly, but rather
 * obtained from a {@link io.reticulum.link.Link} with {@link Link#getChannel()}
 */
@Slf4j
public class Channel {
    private final LinkChannelOutlet outlet;
    private final LinkedList<Envelope> txRing;
    private final LinkedList<Envelope> rxRing;
    private final List<MessageCallbackType> messageCallbacks;
    public final HashMap<Integer,MessageBase> messageFactories;
    private int nextSequence;
    private final int maxTries;

    public Channel(final LinkChannelOutlet linkChannelOutlet) {
        this.outlet = linkChannelOutlet;
        this.txRing = new LinkedList<>();
        this.rxRing = new LinkedList<>();
        this.messageCallbacks = new ArrayList<>();
        this.messageFactories = new HashMap<>();
        this.nextSequence = 0;
        this.maxTries = 5;
    }

    /**
     * Register a message class for reception over a Channel.
     * Message classes must extend MessageBase.
     * 
     * @param messageClass
     */
    public void registerMessageType(MessageBase messageClass, Boolean isSystemType) throws RChannelException {
        if (isNull(messageClass.msgType())) {
            throw new RChannelException(RChannelExceptionType.ME_INVALID_MSG_TYPE, "{} has invalid msgType");
        }
        if ((messageClass.msgType() >= 0xf000) & isFalse(isSystemType)) {
            throw new RChannelException(RChannelExceptionType.ME_INVALID_MSG_TYPE, "{} has system reserved message type"); 
        }
        this.messageFactories.putIfAbsent(messageClass.msgType(), messageClass);
    }

    /**
     * Add a handler for incoming messages. <br/>
     * <p>
     * Handlers are processed in the order they are
     * added. If any handler returns True, processing
     * of the message stops; handlers after the
     * returning handler will not be called.
     *
     * @param callback Function to call
     */
    public synchronized void addMessageHandler(MessageCallbackType callback) {
        if (isFalse(messageCallbacks.contains(callback))) {
            messageCallbacks.add(callback);
        }
    }

    public synchronized void removeMessageHandler(MessageCallbackType callback) {
        messageCallbacks.remove(callback);
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

    public void receive(byte[] raw) {
        try {
            var envelop = new Envelope(outlet, raw);
            synchronized (this) {
                var message = envelop.unpack();
                var prevEnv = rxRing.getFirst();
                if (nonNull(prevEnv) && envelop.getSequence() != (prevEnv.getSequence() + 1) % 0x10000) {
                    log.debug("Channel: Out of order packet received");

                    return;
                }
                var isNew = emplaceEnvelope(envelop, rxRing);
                pruneRxRing();
                if (isFalse(isNew)) {
                    log.debug("Channel: Duplicate message received");

                    return;
                }
                log.debug("Message received: {}", message);
                defaultThreadFactory().newThread(() -> runCallbacks(message)).start();
            }
        } catch (Exception e) {
            log.error("Channel: Error receiving data.", e);
        }
    }

    private void pruneRxRing() {
        // Implementation for fixed window = 1
        var state = rxRing.stream()
                .sorted(comparingInt(Envelope::getSequence).reversed())
                .collect(collectingAndThen(toList(), list -> list.subList(1, list.size())));

        for (Envelope env : state) {
            env.setTracked(true);
            rxRing.remove(env);
        }
    }

    private synchronized void runCallbacks(MessageBase message) {
        for (MessageCallbackType messageCallback : messageCallbacks) {
            try {
                if (messageCallback.apply(message)) {
                    return;
                }
            } catch (Exception e) {
                log.error("Channel: Error running message callback.", e);
            }
        }
    }

    /**
     * Check if {@link Channel} is ready to send.
     *
     * @return True if ready
     */
    public boolean isReadyToSend() {
        if (isFalse(outlet.isUsable())) {
            log.trace("Channel: Link is not usable.");
            return false;
        }

        synchronized (this) {
            for (Envelope envelope : txRing) {
                if (
                        Objects.equals(envelope.getOutlet(), outlet)
                                && isFalse(
                                nonNull(envelope.getPacket())
                                        || outlet.getPacketState(envelope.getPacket()) == MSGSTATE_SENT
                        )
                ) {
                    return false;
                }
            }
        }

        return true;
    }

    private void packetTxOp(Packet packet, Function<Envelope, Boolean> op) {
        var envelop = txRing.stream()
                .filter(e -> Arrays.equals(outlet.getPacketId(e.getPacket()), outlet.getPacketId(packet)))
                .findFirst()
                .orElse(null);

        if (nonNull(envelop) && op.apply(envelop)) {
            envelop.setTracked(true);
            if (isFalse(txRing.remove(envelop))) {
                log.debug("Channel: Envelope not found in TX ring");
            }
        }

        if (isNull(envelop)) {
            log.trace("Channel: Spurious message received");
        }
    }

    private void packetDelivered(Packet packet) {
        packetTxOp(packet, envelope -> true);
    }

    private long getPacketTimeoutTime(int tries) {
        return (long) (Math.pow(2, tries - 1) * Math.max(outlet.rtt(), 100) * 5);
    }

    private void packetTimeout(Packet packet) {
        Function<Envelope, Boolean> retryEnvelope = envelope -> {
            if (envelope.getTries() >= maxTries) {
                log.error("Channel: Retry count exceeded, tearing down Link.");
                shutdown();
                outlet.timedOut();

                return true;
            }
            envelope.setTries(envelope.getTries() + 1);
            outlet.resend(envelope.getPacket());
            outlet.setPacketTimeoutCallback(envelope.getPacket(), this::packetTimeout, getPacketTimeoutTime(envelope.getTries()));

            return false;
        };

        if (outlet.getPacketState(packet) != MSGSTATE_DELIVERED) {
            packetTxOp(packet, retryEnvelope);
        }
    }

    /**
     * Send a message. If a message send is attempted and {@link Channel} is not ready, an exception is thrown.
     *
     * @param message {@link MessageBase}
     * @return {@link Envelope}
     */
    public Envelope send(MessageBase message) {
        Envelope envelope;
        synchronized (this) {
            if (isFalse(isReadyToSend())) {
                throw new IllegalStateException("Link is not ready");
            }
            envelope = new Envelope(outlet, message, nextSequence);
            nextSequence = (nextSequence + 1) % 0x10000;
            emplaceEnvelope(envelope, txRing);
        }

        if (isNull(envelope)) {
            throw new RuntimeException("BlockingIOError");
        }

        try {
            envelope.pack();
        } catch (RChannelException e) {
            log.error("Error packing envelope", e);
        }
        if (getLength(envelope.getRaw()) > outlet.getMdu()) {
            throw new IllegalStateException(
                    String.format("Packed message too big for packet %s > %s", getLength(envelope.getRaw()), outlet.getMdu())
            );
            //throw new RChannelException(RChannelExceptionType.ME_TOO_BIG,
            //    String.format("Packed message too big for packet %s > %s", getLength(envelope.getRaw()), outlet.getMdu())
            //);
        }
        envelope.setPacket(outlet.send(envelope.getRaw()));
        envelope.setTries(envelope.getTries() + 1);
        outlet.setPacketDeliveredCallback(envelope.getPacket(), this::packetDelivered);
        outlet.setPacketTimeoutCallback(envelope.getPacket(), this::packetTimeout, getPacketTimeoutTime(envelope.getTries()));

        return envelope;
    }

    /**
     * Maximum Data Unit: the number of bytes available
     * for a message to consume in a single send. This
     * value is adjusted from the {@link io.reticulum.link.Link} MDU to accommodate
     * message header information.
     *
     * @return number of bytes available
     */
    public int getMdu() {
        return this.outlet.getMdu() - 6;
    }
}
