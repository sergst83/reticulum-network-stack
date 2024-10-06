package io.reticulum.channel;

import io.reticulum.link.Link;
import io.reticulum.message.MessageBase;
//import io.reticulum.message.MessageType;
import io.reticulum.packet.Packet;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static io.reticulum.channel.MessageState.MSGSTATE_DELIVERED;
import static io.reticulum.channel.MessageState.MSGSTATE_SENT;
//import static io.reticulum.utils.IdentityUtils.truncatedHash;
//import static java.util.Comparator.comparingInt;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.concurrent.Executors.defaultThreadFactory;
//import static java.util.stream.Collectors.collectingAndThen;
//import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.ArrayUtils.getLength;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

import static io.reticulum.constant.ChannelConstant.WINDOW_MIN;
import static io.reticulum.constant.ChannelConstant.WINDOW_MIN_LIMIT_MEDIUM;
import static io.reticulum.constant.ChannelConstant.WINDOW_MIN_LIMIT_FAST;
import static io.reticulum.constant.ResourceConstant.FAST_RATE_THRESHOLD;
import static io.reticulum.constant.ChannelConstant.WINDOW_MAX;
import static io.reticulum.constant.ChannelConstant.WINDOW_MAX_FAST;
import static io.reticulum.constant.ChannelConstant.WINDOW_MAX_MEDIUM;
import static io.reticulum.constant.ChannelConstant.WINDOW_MAX_SLOW;
import static io.reticulum.constant.ChannelConstant.RTT_FAST;
import static io.reticulum.constant.ChannelConstant.RTT_MEDIUM;
import static io.reticulum.constant.ChannelConstant.RTT_SLOW;
//import static io.reticulum.constant.ChannelConstant.SEQ_MAX;
import static io.reticulum.constant.ChannelConstant.SEQ_MODULUS;
import static io.reticulum.constant.ChannelConstant.WINDOW;
import static io.reticulum.constant.ChannelConstant.WINDOW_FLEXIBILITY;

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
    //public final HashMap<Integer,MessageBase> messageFactories;
    public Map<Integer,MessageBase> messageFactories;
    private int nextSequence;
    private int nextRxSequence;
    private final int maxTries;
    private int fastRateRounds;
    private int mediumRateRounds;
    private int window;
    private int windowMax;
    private int windowMin;
    private final int windowFlexibility;

    public Channel(final LinkChannelOutlet linkChannelOutlet) {
        this.outlet = linkChannelOutlet;
        this.txRing = new LinkedList<>();
        this.rxRing = new LinkedList<>();
        this.messageCallbacks = new ArrayList<>();
        this.messageFactories = new HashMap<>();
        this.nextSequence = 0;
        this.nextRxSequence = 0;
        this.maxTries = 5;
        this.fastRateRounds = 0;
        this.mediumRateRounds = 0;

        if (linkChannelOutlet.rtt() > RTT_SLOW) {
            this.window            = 1;
            this.windowMax         = 1;
            this.windowMin         = 1;
            this.windowFlexibility = 1;
        } else {
            this.window = WINDOW;
            this.windowMax = WINDOW_MAX_SLOW;
            this.windowMin = WINDOW_MIN;
            this.windowFlexibility = WINDOW_FLEXIBILITY;
        }
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

            if (existing.getSequence() == envelope.getSequence()) {
                log.trace("Envelope: Emplacement of duplicate envelope sequence");
                return false;
            }
            
            if (
                    existing.getSequence() > envelope.getSequence()
                            && isFalse(existing.getSequence() / 2 > envelope.getSequence()) //account for overflow
            ) {
                ring.set(i, envelope);

                envelope.setTracked(true);
                return true;
            }
            i++;
        }
        envelope.setTracked(true);
        ring.add(envelope);

        return true;
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

    public void receive(byte[] raw) {
        try {
            var envelope = new Envelope(outlet, raw);
            synchronized (this) {
                var message = envelope.unpack(this.messageFactories);

                if (envelope.getSequence() < this.nextRxSequence) {
                    var windowOverflow = (this.nextRxSequence + WINDOW_MAX) % SEQ_MODULUS;
                    if (windowOverflow < this.nextRxSequence) {
                        if (envelope.getSequence() > windowOverflow) {log.debug("Channel: Out of order packet received");
                        return;
                        }
                    }
                }

                var isNew = emplaceEnvelope(envelope, rxRing);
                if (isFalse(isNew)) {
                    log.debug("Channel: Duplicate message received");
                    return;
                } else {
                    var contiguous = new ArrayList<Envelope>();
                    for (Envelope e : rxRing) {
                        if (e.getSequence() == nextRxSequence) {
                            contiguous.add(e);
                            this.nextRxSequence = (nextRxSequence +1 ) % SEQ_MODULUS;
                            if (this.nextRxSequence == 0) {
                                for (Envelope rxEnvelope: this.rxRing) {
                                    if (rxEnvelope.getSequence() == this.nextSequence) {
                                        contiguous.add(rxEnvelope);
                                        this.nextRxSequence = (this.nextRxSequence + 1) % SEQ_MODULUS;
                                    }
                                }
                            }
                        }
                    }
                    MessageBase m;
                    for (Envelope e: contiguous) {
                        if (isFalse(e.isUnpacked())) {
                            m = e.unpack(this.messageFactories);
                        } else {
                            m = e.getMessage();
                        }
                        this.rxRing.remove(e);
                        this.runCallbacks(m);
                    }
                }
                log.debug("Message received: {}", message);
                //defaultThreadFactory().newThread(() -> runCallbacks(message)).start();
            }
        } catch (Exception e) {
            log.error("Channel: Error receiving data.", e);
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
        var envelope = txRing.stream()
                .filter(e -> Arrays.equals(outlet.getPacketId(e.getPacket()), outlet.getPacketId(packet)))
                .findFirst()
                .orElse(null);

        synchronized(this) {
            if (nonNull(envelope) && op.apply(envelope)) {
                envelope.setTracked(false);

                if (isFalse(txRing.remove(envelope))) {
                    log.debug("Channel: Envelope not found in TX ring");
                } else {
                    if (this.window < this.windowMax) {
                        this.window += 1;
                    }
                    if (this.outlet.rtt() != 0) {
                        if (this.outlet.rtt() > RTT_FAST) {
                            this.fastRateRounds = 0;
                            if (this.outlet.rtt() > RTT_MEDIUM) {
                                this.mediumRateRounds = 0;
                            } else {
                                this.mediumRateRounds += 1;
                                if ((this.windowMax < WINDOW_MAX_MEDIUM) && (this.mediumRateRounds == FAST_RATE_THRESHOLD)) {
                                    this.windowMax = WINDOW_MAX_MEDIUM;
                                    this.windowMin = WINDOW_MIN_LIMIT_MEDIUM;
                                }
                            }
                        }
                        else {
                            this.fastRateRounds += 1;
                            if ((this.windowMax < WINDOW_MAX_FAST) && (this.fastRateRounds == FAST_RATE_THRESHOLD)){
                                this.windowMax = WINDOW_MAX_FAST;
                                this.windowMin = WINDOW_MIN_LIMIT_FAST;
                            }
                        }
                    } else {
                        log.trace("Envelope not found in TX ring for {}", this);
                    }
                }
            }
        }

        if (isNull(envelope)) {
            log.trace("Channel: Spurious message received on {}", this);
        }
    }

    private void packetDelivered(Packet packet) {
        packetTxOp(packet, envelope -> true);
    }

    private void updatePacketTimeouts() {
        for (Envelope e: this.txRing) {
            var updatedTimeout = getPacketTimeoutTime(e.getTries());
            var ep = e.getPacket();
            var epr = ep.getReceipt();

            if (nonNull(e.getPacket()) && (nonNull(epr) && nonNull(epr.getTimeout()))) {
                if (updatedTimeout > epr.getTimeout()) {
                    epr.setTimeout(updatedTimeout);
                }
            }
        }
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
            outlet.setPacketDeliveredCallback(envelope.getPacket(), this::packetDelivered);
            outlet.setPacketTimeoutCallback(envelope.getPacket(), this::packetTimeout, getPacketTimeoutTime(envelope.getTries()));
            updatePacketTimeouts();

            if (this.window > this.windowMin) {
                this.window = this.window - 1;

                if (this.windowMax > (this.windowMin + this.windowFlexibility)) {
                    this.windowMax = this.windowMax - 1;
                }
            }

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
                //throw new RChannelException(RChannelExceptionType.ME_LINK_NOT_READY, "Link is not ready");
            }
            envelope = new Envelope(outlet, message, nextSequence);
            nextSequence = (nextSequence + 1) % SEQ_MODULUS;
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
