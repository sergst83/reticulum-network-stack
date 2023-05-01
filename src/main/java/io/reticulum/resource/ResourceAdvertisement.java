package io.reticulum.resource;

import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.lang3.ArrayUtils;
import org.msgpack.core.MessagePack;
import org.msgpack.value.ImmutableStringValue;
import org.msgpack.value.ImmutableValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;

import static io.reticulum.constant.ResourceConstant.HASHMAP_MAX_LEN;
import static io.reticulum.constant.ResourceConstant.MAPHASH_LEN;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNullElse;
import static org.apache.commons.lang3.ArrayUtils.getLength;
import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.apache.commons.lang3.BooleanUtils.toInteger;
import static org.msgpack.value.ValueFactory.newBinary;
import static org.msgpack.value.ValueFactory.newInteger;
import static org.msgpack.value.ValueFactory.newMap;
import static org.msgpack.value.ValueFactory.newString;

@Data
@NoArgsConstructor
public class ResourceAdvertisement {

    private int t;
    private int d;
    private int n;
    private byte[] h;
    private byte[] r;
    private byte[] o;
    private byte[] m;
    private boolean c;
    private boolean e;
    private boolean s;
    private int i;
    private int l;
    private byte[] q;
    private boolean u;
    private boolean p;
    private int f;

    private Link link;

    public ResourceAdvertisement(Resource resource) {
        if (nonNull(resource)) {
            this.t = resource.getSize();                // Transfer size
            this.d = resource.getTotalSize();           // Total uncompressed data size
            this.n = getLength(resource.getParts());    // Number of parts
            this.h = resource.getHash();                // Resource hash
            this.r = resource.getRandomHash();          // Resource random hash
            this.o = resource.getOriginalHash();        // First-segment hash
            this.m = resource.getHashmap();             // Resource hashmap
            this.c = resource.isCompressed();           // Compression flag
            this.e = resource.isEncrypted();            // Encryption flag
            this.s = resource.isSplit();                // Split flag
            this.i = resource.getSegmentIndex();        // Segment index
            this.l = resource.getTotalSegments();       // Total segments
            this.q = resource.getRequestId();           // ID of associated request
            this.u = false;                             // Is request flag
            this.p = false;                             // Is response flag

            if (nonNull(q)) {
                if (isFalse(resource.isResponse())) {
                    this.u = true;
                    this.p = false;
                } else {
                    this.u = false;
                    this.p = true;
                }
            }

            // Flags
            this.f = 0x00 | toInteger(this.p) << 4 | toInteger(this.u) << 3 | toInteger(this.s) << 2 | toInteger(this.c) << 1 | toInteger(this.e);
        }
    }

    public int getTransferSize() {
        return this.t;
    }

    public int getDataSize() {
        return this.d;
    }

    public int  getParts() {
        return this.n;
    }

    public int getSegments() {
        return this.l;
    }

    public byte[] getHash() {
        return this.h;
    }

    public boolean isCompressed() {
        return this.c;
    }

    public static boolean isRequest(@NonNull final Packet advertisementPacket) {
        var adv = unpack(advertisementPacket.getPlaintext());

        return nonNull(adv) && nonNull(adv.q) && adv.u;
    }

    public static boolean isResponse(@NonNull final Packet advertisementPacket) {
        var adv = unpack(advertisementPacket.getPlaintext());

        return nonNull(adv) && nonNull(adv.q) && adv.p;
    }

    public static byte[] readRequestId(@NonNull final Packet advertisementPacket) {
        var adv = unpack(advertisementPacket.getPlaintext());

        if (nonNull(adv)) {
            return adv.q;
        }

        return null;
    }

    public static int readSize(@NonNull final Packet advertisementPacket) {
        var adv = unpack(advertisementPacket.getPlaintext());

        if (nonNull(adv)) {
            return adv.d;
        }

        return 0;
    }

    public static int readTransferSize(@NonNull final Packet advertisementPacket) {
        var adv = unpack(advertisementPacket.getPlaintext());

        if (nonNull(adv)) {
            return adv.t;
        }

        return 0;
    }

    public byte[] pack(Integer segments) {
        int localSegment = requireNonNullElse(segments, 0);
        var hashmapStart = localSegment * HASHMAP_MAX_LEN;
        var hashmapEnd = Math.min((localSegment + 1) * HASHMAP_MAX_LEN, n);

        var hashMap = new byte[0];
        try (var baos = new ByteArrayOutputStream()) {
            for (int i = (int) hashmapStart; i < hashmapEnd; i++) {
                baos.writeBytes(ArrayUtils.subarray(m, i * MAPHASH_LEN, (i + 1) * MAPHASH_LEN));
            }

            hashMap = baos.toByteArray();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        var dictionary = new HashMap<ImmutableStringValue, ImmutableValue>();
        dictionary.put(newString("t"), newInteger(t));  // Transfer size
        dictionary.put(newString("d"), newInteger(d));  // Data size
        dictionary.put(newString("n"), newInteger(n));  // Number of parts
        dictionary.put(newString("h"), newBinary(h));   // Resource hash
        dictionary.put(newString("r"), newBinary(r));   // Resource random hash
        dictionary.put(newString("o"), newBinary(o));   // Original hash
        dictionary.put(newString("i"), newInteger(i));  // Segment index
        dictionary.put(newString("l"), newInteger(l));  // Total segments
        dictionary.put(newString("q"), newBinary(q));   // Request ID
        dictionary.put(newString("f"), newInteger(f));  // Resource flags
        dictionary.put(newString("m"), newBinary(hashMap));

        try (var packer = MessagePack.newDefaultBufferPacker()) {
            packer.packValue(newMap(dictionary));

            return packer.toByteArray();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @SneakyThrows
    public static ResourceAdvertisement unpack(byte[] plaintext) {
        if (isNull(plaintext)) {
            return null;
        }

        var adv = new ResourceAdvertisement();
        try (var unpacker = MessagePack.newDefaultUnpacker(plaintext)) {
            var dictionary = unpacker.unpackValue().asMapValue().map();

            adv.t = dictionary.get(newString("t")).asIntegerValue().asInt();
            adv.d = dictionary.get(newString("d")).asIntegerValue().asInt();
            adv.n = dictionary.get(newString("n")).asIntegerValue().asInt();
            adv.h = dictionary.get(newString("h")).asBinaryValue().asByteArray();
            adv.r = dictionary.get(newString("r")).asBinaryValue().asByteArray();
            adv.o = dictionary.get(newString("o")).asBinaryValue().asByteArray();
            adv.m = dictionary.get(newString("m")).asBinaryValue().asByteArray();
            adv.f = dictionary.get(newString("f")).asIntegerValue().asInt();
            adv.i = dictionary.get(newString("i")).asIntegerValue().asInt();
            adv.l = dictionary.get(newString("l")).asIntegerValue().asInt();
            adv.q = dictionary.get(newString("q")).asBinaryValue().asByteArray();
            adv.e = (adv.f & 0x01) == 0x01;
            adv.c = ((adv.f >> 1) & 0x01) == 0x01;
            adv.s = ((adv.f >> 2) & 0x01) == 0x01;
            adv.u = ((adv.f >> 3) & 0x01) == 0x01;
            adv.p = ((adv.f >> 4) & 0x01) == 0x01;
        }

        return adv;
    }
}
