package io.reticulum.utils;

import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import org.msgpack.core.MessagePack;
import org.msgpack.jackson.dataformat.MessagePackMapper;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.isNull;
import static lombok.AccessLevel.PRIVATE;

/**
 * Converts between plain Java objects and msgpack values the way the
 * reference's {@code umsgpack} does.
 * <p>
 * Jackson's {@code MessagePackMapper} is not suitable for structures exchanged
 * with Python RNS applications: it writes every map key as a msgpack
 * <em>str</em>, so a Python {@code {1: 0}} would come back as {@code {'1': 0}}
 * and the reverse direction delivers integer keys as Java strings. rngit keys
 * its response metadata and request fields by integer
 * ({@code IDX_RESULT_CODE = 0x01}, {@code IDX_REPOSITORY = 0x00}), so those
 * structures have to round-trip with their key types intact.
 *
 * <p>Java to msgpack: null → nil, {@code Boolean} → bool, integral numbers →
 * int (smallest encoding), {@code Float}/{@code Double} → float,
 * {@code String} → str, {@code byte[]} → bin, {@code Map} → map,
 * {@code Collection} and arrays → array, a msgpack {@link Value} as-is. Any
 * other object is serialised with Jackson and embedded as the resulting value.
 *
 * <p>msgpack to Java: nil → null, bool → {@code Boolean}, int → {@code Long}
 * (or {@code BigInteger} above {@code Long.MAX_VALUE}), float → {@code Double},
 * str → {@code String}, bin → {@code byte[]}, array → {@code List},
 * map → {@code LinkedHashMap} (keys converted the same way), ext → the
 * msgpack {@link Value}.
 */
@NoArgsConstructor(access = PRIVATE)
public final class MsgPackUtils {

    private static final MessagePackMapper FALLBACK_MAPPER = new MessagePackMapper();

    public static Value toValue(final Object object) {
        if (isNull(object)) {
            return ValueFactory.newNil();
        }
        if (object instanceof Value) {
            return (Value) object;
        }
        if (object instanceof byte[]) {
            return ValueFactory.newBinary((byte[]) object);
        }
        if (object instanceof String) {
            return ValueFactory.newString((String) object);
        }
        if (object instanceof Boolean) {
            return ValueFactory.newBoolean((Boolean) object);
        }
        if (object instanceof BigInteger) {
            return ValueFactory.newInteger((BigInteger) object);
        }
        if (object instanceof Long || object instanceof Integer || object instanceof Short || object instanceof Byte) {
            return ValueFactory.newInteger(((Number) object).longValue());
        }
        if (object instanceof Double || object instanceof Float) {
            return ValueFactory.newFloat(((Number) object).doubleValue());
        }
        if (object instanceof Map) {
            var map = new LinkedHashMap<Value, Value>();
            ((Map<?, ?>) object).forEach((k, v) -> map.put(toValue(k), toValue(v)));

            return ValueFactory.newMap(map);
        }
        if (object instanceof Collection) {
            var list = new ArrayList<Value>();
            ((Collection<?>) object).forEach(e -> list.add(toValue(e)));

            return ValueFactory.newArray(list);
        }
        if (object instanceof Object[]) {
            var list = new ArrayList<Value>();
            for (var e : (Object[]) object) {
                list.add(toValue(e));
            }

            return ValueFactory.newArray(list);
        }

        return unpack(fallbackPack(object));
    }

    public static Object fromValue(final Value value) {
        if (isNull(value) || value.isNilValue()) {
            return null;
        }

        switch (value.getValueType()) {
            case BOOLEAN:
                return value.asBooleanValue().getBoolean();
            case INTEGER:
                var integer = value.asIntegerValue();
                return integer.isInLongRange() ? (Object) integer.toLong() : integer.toBigInteger();
            case FLOAT:
                return value.asFloatValue().toDouble();
            case STRING:
                // A str that is not valid UTF-8 is still delivered, as umsgpack
                // would raise; keep the bytes decodable rather than failing here.
                return new String(value.asStringValue().asByteArray(), UTF_8);
            case BINARY:
                return value.asBinaryValue().asByteArray();
            case ARRAY:
                List<Object> list = new ArrayList<>();
                value.asArrayValue().forEach(e -> list.add(fromValue(e)));
                return list;
            case MAP:
                Map<Object, Object> map = new LinkedHashMap<>();
                value.asMapValue().map().forEach((k, v) -> map.put(fromValue(k), fromValue(v)));
                return map;
            default:
                return value;
        }
    }

    @SneakyThrows
    public static byte[] pack(final Value value) {
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            packer.packValue(value);

            return packer.toByteArray();
        }
    }

    public static byte[] packObject(final Object object) {
        return pack(toValue(object));
    }

    @SneakyThrows
    public static Value unpack(final byte[] packed) {
        try (var unpacker = MessagePack.newDefaultUnpacker(packed)) {
            return unpacker.unpackValue();
        }
    }

    public static Object unpackObject(final byte[] packed) {
        return fromValue(unpack(packed));
    }

    @SneakyThrows
    private static byte[] fallbackPack(final Object object) {
        return FALLBACK_MAPPER.writeValueAsBytes(object);
    }
}
