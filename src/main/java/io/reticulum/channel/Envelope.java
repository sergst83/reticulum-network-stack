package io.reticulum.channel;

import io.reticulum.message.MessageBase;
import io.reticulum.message.MessageFactory;
import io.reticulum.packet.Packet;
import lombok.Data;
import org.apache.commons.lang3.ArrayUtils;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;

import static io.reticulum.utils.IdentityUtils.concatArrays;
import static java.util.Objects.isNull;

/**
 *     Internal wrapper used to transport messages over a channel and
 *     track its state within the channel framework.
 */
@Data
public class Envelope {
    private MessageBase message;
    private final UUID id;
    private Packet packet;
    private LinkChannelOutlet outlet;
    private int tries;
    private byte[] raw;
    private Integer sequence;
    private boolean tracked;
    private Instant ts;
    private boolean packed;
    private boolean unpacked;

    private Envelope(LinkChannelOutlet outlet, MessageBase message, byte[] raw, Integer sequence) {
        this.ts = Instant.now();
        this.id = UUID.randomUUID();
        this.message = message;
        this.raw = raw;
        this.packet = null;
        this.sequence = sequence;
        this.outlet = outlet;
        this.tries = 0;
        this.tracked = false;
        this.packed = false;
        this.unpacked = false;
    }

    public Envelope(LinkChannelOutlet outlet, byte[] raw) {
        this(outlet, null, raw, null);
    }

    public Envelope(LinkChannelOutlet outlet, MessageBase message, Integer sequence) {
        this(outlet, message, null, sequence);
    }

    public MessageBase unpack() {
        var buffer = ByteBuffer.wrap(ArrayUtils.subarray(this.raw, 0, 6));
        var msgtype = (int) buffer.getShort(0);
        this.sequence = (int) buffer.getShort(2);
        var length = (int) buffer.getShort(4);

        var raw = ArrayUtils.subarray(this.raw, 6, this.raw.length);
        message = MessageFactory.getInstance(msgtype);
        message.unpack(raw);
        unpacked = true;

        return message;
    }

    public byte[] pack() {
        if (isNull(message.msgType())) {
            throw new IllegalStateException("message has no type");
        }
        var data = message.pack();
        var buffer = ByteBuffer.allocate(6)
                .putShort(message.msgType().shortValue())
                .putShort(sequence.shortValue())
                .putShort(Integer.valueOf(data.length).shortValue());
        raw = concatArrays(buffer.array(), data);
        packed = true;

        return this.raw;
    }
}
