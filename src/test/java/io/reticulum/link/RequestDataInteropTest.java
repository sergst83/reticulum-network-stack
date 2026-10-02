package io.reticulum.link;

import io.reticulum.destination.Request;
import io.reticulum.utils.MsgPackUtils;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request data is whatever the requester packs as {@code data}
 * ({@code RNS/Link.py:487}). rngit sends a map on every request, so a Java
 * responder that only accepted msgpack bin threw on all of them.
 * <p>
 * Vectors are {@code umsgpack.packb(...)} output from Python RNS 1.5.4.
 */
class RequestDataInteropTest {

    /** truncated_hash(b"/git/fetch") */
    private static final String FETCH_PATH_HASH = "1c556030141115b9547bf70aaed42e1c";

    private static final Instant REQUESTED_AT = Instant.ofEpochMilli(1_700_000_000_500L);

    /**
     * [1700000000.5, path_hash, {0: "public/myrepo",
     *   "refs": [{"ref": "refs/heads/master", "have": "a"*40}], "have": []}]
     */
    private static final String REFERENCE_FETCH_REQUEST =
            "93cb41d954fc40200000c4101c556030141115b9547bf70aaed42e1c8300ad7075626c69632f6d797265706f"
                    + "a4726566739182a3726566b1726566732f68656164732f6d6173746572a468617665d928"
                    + "61616161616161616161616161616161616161616161616161616161616161616161616161616161"
                    + "a46861766590";

    /**
     * {0: "public/myrepo", "local_ref": "refs/heads/master", "remote_ref": "refs/heads/master",
     *  "force": False, "bundle": bytes(range(8))}
     */
    private static final String REFERENCE_PUSH_DATA =
            "8500ad7075626c69632f6d797265706fa96c6f63616c5f726566b1726566732f68656164732f6d6173746572"
                    + "aa72656d6f74655f726566b1726566732f68656164732f6d6173746572a5666f726365c2"
                    + "a662756e646c65c4080001020304050607";

    /** [1700000000.5, path_hash, None] */
    private static final String REFERENCE_NIL_REQUEST =
            "93cb41d954fc40200000c4101c556030141115b9547bf70aaed42e1cc0";

    private static Map<Object, Object> fetchData() {
        var ref = new LinkedHashMap<Object, Object>();
        ref.put("ref", "refs/heads/master");
        ref.put("have", "a".repeat(40));

        var data = new LinkedHashMap<Object, Object>();
        data.put(0, "public/myrepo");
        data.put("refs", List.of(ref));
        data.put("have", List.of());

        return data;
    }

    private static Map<Object, Object> pushData() {
        var data = new LinkedHashMap<Object, Object>();
        data.put(0, "public/myrepo");
        data.put("local_ref", "refs/heads/master");
        data.put("remote_ref", "refs/heads/master");
        data.put("force", false);
        data.put("bundle", new byte[]{0, 1, 2, 3, 4, 5, 6, 7});

        return data;
    }

    private static UnpackedRequest parse(String hex) throws Exception {
        return UnpackedRequest.fromValue(MsgPackUtils.unpack(Hex.decodeHex(hex)).immutableValue().asArrayValue());
    }

    private static Request asRequest(UnpackedRequest unpacked) {
        return new Request("/git/fetch", unpacked.getData(), new byte[16], new byte[16], null, unpacked.getTime());
    }

    @Test
    @DisplayName("A Java requester packs a map request byte-identically to the reference")
    void mapRequestPacksLikeReference() throws Exception {
        var unpacked = new UnpackedRequest(REQUESTED_AT, Hex.decodeHex(FETCH_PATH_HASH), MsgPackUtils.toValue(fetchData()));

        assertEquals(REFERENCE_FETCH_REQUEST, Hex.encodeHexString(MsgPackUtils.pack(unpacked.toValue())));
    }

    @Test
    @DisplayName("A reference map request reaches the handler with its keys and types intact")
    void referenceMapRequestDecodes() throws Exception {
        var request = asRequest(parse(REFERENCE_FETCH_REQUEST));

        @SuppressWarnings("unchecked")
        var data = (Map<Object, Object>) request.getDataObject();
        assertEquals("public/myrepo", data.get(0L), "integer key 0 stays an integer");
        assertEquals(List.of(), data.get("have"));

        @SuppressWarnings("unchecked")
        var ref = (Map<Object, Object>) ((List<Object>) data.get("refs")).get(0);
        assertEquals("refs/heads/master", ref.get("ref"));
        assertEquals("a".repeat(40), ref.get("have"));
        assertEquals(REQUESTED_AT, request.getRequestedAt());
    }

    @Test
    @DisplayName("Push data packs like the reference and its bundle comes back as bytes")
    void pushDataRoundTrips() throws Exception {
        assertEquals(REFERENCE_PUSH_DATA, Hex.encodeHexString(MsgPackUtils.packObject(pushData())));

        @SuppressWarnings("unchecked")
        var decoded = (Map<Object, Object>) MsgPackUtils.unpackObject(Hex.decodeHex(REFERENCE_PUSH_DATA));
        assertEquals(Boolean.FALSE, decoded.get("force"));
        assertArrayEquals(new byte[]{0, 1, 2, 3, 4, 5, 6, 7}, (byte[]) decoded.get("bundle"));
    }

    @Test
    @DisplayName("getData() keeps the old byte view: raw bytes for bin, msgpack for a structure")
    void byteViewOfRequestData() throws Exception {
        var bin = new Request("/x", MsgPackUtils.toValue(new byte[]{9, 8, 7}), null, null, null, REQUESTED_AT);
        assertArrayEquals(new byte[]{9, 8, 7}, bin.getData());

        var map = asRequest(parse(REFERENCE_FETCH_REQUEST));
        var reencoded = MsgPackUtils.unpack(map.getData());
        assertTrue(reencoded.isMapValue());
        assertEquals(MsgPackUtils.toValue(fetchData()), reencoded);
    }

    @Test
    @DisplayName("A data-less request packs and parses as the reference's nil")
    void nilRequest() throws Exception {
        var unpacked = new UnpackedRequest(REQUESTED_AT, Hex.decodeHex(FETCH_PATH_HASH), MsgPackUtils.toValue(null));
        assertEquals(REFERENCE_NIL_REQUEST, Hex.encodeHexString(MsgPackUtils.pack(unpacked.toValue())));

        var request = asRequest(parse(REFERENCE_NIL_REQUEST));
        assertNull(request.getData());
        assertNull(request.getDataObject());
    }
}
