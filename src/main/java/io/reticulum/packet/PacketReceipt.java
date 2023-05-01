package io.reticulum.packet;

import io.reticulum.Transport;
import io.reticulum.constant.PacketConstant;
import io.reticulum.destination.AbstractDestination;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.link.Link;
import lombok.Data;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.Consumer;

import static io.reticulum.constant.IdentityConstant.HASHLENGTH;
import static io.reticulum.constant.IdentityConstant.SIGLENGTH;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.concurrent.Executors.defaultThreadFactory;
import static org.apache.commons.lang3.ArrayUtils.subarray;

@Data
@Slf4j
public class PacketReceipt {
    private static final int EXPL_LENGTH = HASHLENGTH / 8 + SIGLENGTH / 8;
    private static final int IMPL_LENGTH = SIGLENGTH / 8;

    private AbstractDestination destination;
    private Instant sentAt;
    private boolean sent;
    private byte[] hash;
    /**
     * in milliseconds
     */
    private long timeout;
    private byte[] truncatedHash;
    private Consumer<PacketReceipt> timeoutCallback;
    private PacketReceiptStatus status;
    private boolean proved;
    private Instant concludedAt;
    private PacketReceiptCallbacks callbacks;
    private Packet proofPacket;

    public PacketReceipt(Packet packet) {
        this.hash = packet.getHash();
        this.truncatedHash = packet.getTruncatedHash();
        this.sent = true;
        this.sentAt = Instant.now();
        this.proved = false;
        this.status = PacketReceiptStatus.SENT;
        this.destination = packet.getDestination();
        this.callbacks = new PacketReceiptCallbacks();

        if (packet.getDestination().getType() == DestinationType.LINK) {
            this.timeout = ((Link) packet.getDestination()).getRtt() * ((Link) packet.getDestination()).getTrafficTimeoutFactor();
        } else {
            this.timeout = Duration.ofSeconds(PacketConstant.TIMEOUT_PER_HOP).toMillis() * Transport.getInstance().hopsTo(destination.getHash());
        }
    }

    public synchronized boolean validateProofPacket(@NonNull final Packet proofPacket) {
        if (proofPacket.getDestination().getType() == DestinationType.LINK) {
            return validateLinkProof((Link) proofPacket.getDestination(), proofPacket);
        } else {
            return  validateProof(proofPacket.getData(), proofPacket);
        }
    }

    public synchronized boolean validateProof(@NonNull byte[] proof, final Packet proofPacket) {
        var dest = (Destination) destination;
        if (proof.length == EXPL_LENGTH) {
            //This is an explicit proof
            var proofHash = subarray(proof, 0, HASHLENGTH / 8);
            var signature = subarray(proof, HASHLENGTH / 8, HASHLENGTH / 8 + SIGLENGTH / 8);
            if (Arrays.equals(proofHash, hash)) {
                if (dest.getIdentity().validate(signature, hash)) {
                    status = PacketReceiptStatus.DELIVERED;
                    proved = true;
                    concludedAt = Instant.now();
                    this.proofPacket = proofPacket;

                    if (nonNull(callbacks.getDelivery())) {
                        try {
                            callbacks.getDelivery().accept(this);
                        } catch (Exception e) {
                            log.error("Error while executing proof validated callback.");
                        }
                    }

                    return true;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        } else if (proof.length == IMPL_LENGTH) {
            // This is an implicit proof
            if (isNull(dest.getIdentity())) {
                return false;
            }

            var signature = subarray(proof, 0, SIGLENGTH / 8);
            if (dest.getIdentity().validate(signature, hash)) {
                status = PacketReceiptStatus.DELIVERED;
                proved = true;
                concludedAt = Instant.now();
                this.proofPacket = proofPacket;

                if (nonNull(callbacks.getDelivery())) {
                    try {
                        callbacks.getDelivery().accept(this);
                    } catch (Exception e) {
                        log.error("Error while executing proof validated callback");
                    }
                }

                return true;
            } else {
                return false;
            }
        }

        return false;
    }

    /**
     * Validate a raw proof for a link
     *
     * @param link
     * @param proofPacket
     * @return
     */
    private boolean validateLinkProof(@NonNull final Link link, @NonNull final Packet proofPacket) {
        var proof = proofPacket.getData();
        if (proof.length == EXPL_LENGTH) {
            //This is an explicit proof
            var proofHash = subarray(proof, 0, HASHLENGTH / 8);
            var signature = subarray(proof, HASHLENGTH / 8, HASHLENGTH / 8 + SIGLENGTH / 8);
            if (Arrays.equals(proofHash, hash)) {
                if (link.validate(signature, hash)) {
                    status = PacketReceiptStatus.DELIVERED;
                    proved = true;
                    concludedAt = Instant.now();
                    this.proofPacket = proofPacket;

                    if (nonNull(callbacks.getDelivery())) {
                        callbacks.getDelivery().accept(this);
                    }

                    return true;
                } else {
                    return false;
                }
            } else {
                return  false;
            }
        }

        return false;
    }

    /**
     * @return The round-trip-time in milliseconds
     */
    public long getRtt() {
        return Duration.between(sentAt, concludedAt).toMillis();
    }

    public boolean isTimedOut() {
        return sentAt.plusMillis(timeout).isBefore(Instant.now());
    }

    public synchronized void checkTimeout() {
        if (status == PacketReceiptStatus.SENT && isTimedOut()) {
            if (timeout == -1) {
                status = PacketReceiptStatus.CULLED;
            } else {
                status = PacketReceiptStatus.FAILED;
            }

            concludedAt = Instant.now();

            if (nonNull(callbacks.getTimeout())) {
                defaultThreadFactory()
                        .newThread(() -> callbacks.getTimeout().accept(this))
                        .start();
            }
        }
    }

    public synchronized void setDeliveryCallback(Consumer<PacketReceipt> deliveryCallback) {
        callbacks.setDelivery(deliveryCallback);
    }

    public synchronized void setTimeoutCallback(Consumer<PacketReceipt> timeoutCallback) {
        callbacks.setTimeout(timeoutCallback);
    }
}
