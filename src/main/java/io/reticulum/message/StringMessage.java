package io.reticulum.message;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.msgpack.core.MessagePack;
import org.msgpack.value.ValueFactory;

import java.io.IOException;
import java.time.Instant;

/**
 *      The MSGTYPE class variable needs to be assigned a
 *      2 byte integer value. This identifier allows the
 *      channel to look up your message's constructor when a
 *      message arrives over the channel.
 *
 *      MSGTYPE must be unique across all message types we
 *      register with the channel. MSGTYPEs >= 0xf000 are
 *      reserved for the system.
 */
@Data
@NoArgsConstructor
public class StringMessage extends MessageBase{

    private byte[] data;
    private Instant timestamp;

    public StringMessage(byte[] data) {
        this.data = data;
        this.timestamp = Instant.now();
    }

    @Override
    public Integer msgType() {
        return 0x0101;
        //MessageType stringType = MessageType.STRING;
        //return stringType.getMsgType();
    }

    @Override
    public byte[] pack() {
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            packer.packValue(
                    ValueFactory.newArray(
                            ValueFactory.newBinary(data),
                            ValueFactory.newTimestamp(timestamp)
                    )
            );

            return packer.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void unpack(byte[] raw) {
        try (var unpacker = MessagePack.newDefaultUnpacker(raw)) {
            var arr = unpacker.unpackValue().asArrayValue();
            this.data = arr.get(0).asBinaryValue().asByteArray();
            this.timestamp = arr.get(1).asTimestampValue().toInstant();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
