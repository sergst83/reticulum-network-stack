package io.reticulum.channel;

import io.reticulum.constant.LinkConstant;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;
import io.reticulum.packet.Packet;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;

import java.util.function.Consumer;

import static io.reticulum.channel.MessageState.MSGSTATE_FAILED;
import static io.reticulum.packet.PacketContextType.CHANNEL;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * An implementation of ChannelOutletBase for {@link Link}.
 * Allows {@link Channel} to send packets over an {@link Link} with
 * Packets.
 * 
 * Note: ChannelOutletBase in Python is marked as deprecated.
 *       Therefore this class explicitly implements all methods.
 */
@RequiredArgsConstructor
@Slf4j
public class LinkChannelOutlet {

    private final Link link;

    public Packet send(byte[] raw) {
        var packet = new Packet(link, raw, CHANNEL);
        if (link.getStatus() == LinkStatus.ACTIVE) {
            packet.send();
        }

        return packet;
    }

    public Packet resend(@NonNull final Packet packet) {
        log.debug("Resending packet {}", Hex.encodeHexString(packet.getPacketHash()));
        if (isNull(packet.resend())) {
            log.error("Failed to resend packet");
        }

        return packet;
    }

    public double mdu() {
        return LinkConstant.MDU;
    }

    public long rtt() {
        return link.getRtt();
    }

    public boolean isUsable() {
        return true; //had issues looking at Link.status
    }

    public MessageState getPacketState(TPacket packet) {
        if (isNull(packet.getReceipt())) {
            return MSGSTATE_FAILED;
        }

        var status = packet.getReceipt().getStatus();
        switch (status) {
            case SENT:
                return MessageState.MSGSTATE_SENT;
            case DELIVERED:
                return MessageState.MSGSTATE_DELIVERED;
            case FAILED:
                return MSGSTATE_FAILED;
            default:
                throw new IllegalStateException(String.format("Unexpected receipt state %s", status));
        }
    }

    public void timedOut() {
        link.teardown();
    }

    public void setPacketTimeoutCallback(@NonNull final Packet packet, final Consumer<Packet> callback, final Long timeout) {
        if (nonNull(timeout) && nonNull(packet.getReceipt())) {
            packet.getReceipt().setTimeout(timeout);
        }

        if (nonNull(packet.getReceipt())) {
            packet.getReceipt().setTimeoutCallback(
                    isNull(callback) ? null : packetReceipt -> callback.accept(packet)
            );
        }
    }

    public void setPacketDeliveredCallback(@NonNull final Packet packet, final Consumer<Packet> callback) {
        if (nonNull(packet.getReceipt())) {
            packet.getReceipt().setDeliveryCallback(
                    isNull(callback) ? null : packetReceipt -> callback.accept(packet)
            );
        }
    }

    public byte[] getPacketId(@NonNull final Packet packet) {
        return packet.getHash();
    }

    public String toString() {
        return String.format(this.getClass().getSimpleName() + "(%s)", link);
    }

    public int getMdu() {
        return LinkConstant.MDU;
    }
}
