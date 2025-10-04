package io.reticulum.link;

//import io.reticulum.destination.DestinationType;
//import io.reticulum.transport.TransportType;

import examples.LinkApp;
import io.reticulum.Reticulum;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.value.ValueFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static io.reticulum.link.LinkStatus.ACTIVE;
import static org.apache.commons.codec.binary.Hex.encodeHexString;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

//import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class LinkTest {

    LinkApp node1;
    LinkApp node2;
    LinkStatus linkStatus;

    @BeforeAll
    static void init() {
        System.setProperty("io.netty.tryReflectionSetAccessible", "true");
    }

    @BeforeAll
    static void starLocalSertver() throws IOException {
        var server = new Reticulum("src/test/resources/tcp_server_node");
    }

    //todo fix it
    @Test
    @Disabled
    void testLinkStatus() {
        node1 = new LinkApp("src/test/resources/node1");
        node2 = new LinkApp("src/test/resources/node2");
        node1.serverSetup();
        var destinationHash = node1.getBaseDestination().getHash();
        log.info("server hash: {}", encodeHexString(destinationHash));

        node1.getBaseDestination().announce();
        var probeCount = 1;
        var maxProbeCount = 3;
        while (isFalse(node2.getTransportInstance().hasPath(destinationHash)) && (probeCount < maxProbeCount)) {
            log.info("probe {} - Destination is not yet known. Requesting path and waiting for announce to arrive...", probeCount);
            node1.getBaseDestination().announce();
            try {
                TimeUnit.MILLISECONDS.sleep(5000);
                probeCount += 1;
            } catch (InterruptedException e) {
                log.info("sleep interrupted: {}", probeCount, e);
            }
        }
        if (isFalse(node2.getTransportInstance().hasPath(destinationHash))) {
            log.info("no path for {}", encodeHexString(destinationHash));
        }
        node2.clientSetup(node1.getBaseDestination().getHash());
        linkStatus = node2.getClientLink().getStatus();
        log.info("link status: {}", linkStatus);

        Assertions.assertEquals(ACTIVE, linkStatus);

        // clean up
        node1.shutdown();
        node2.shutdown();
    }

    @Test
    void packUnpackTest() throws IOException {
        var responseData = "responseData".getBytes();
        var requestId = "requestId".getBytes();

        @Cleanup var packer = MessagePack.newDefaultBufferPacker();
        var packedResp = new PackedResponse(requestId, responseData);
        var value = packedResp.toValue();
        packer.packValue(value);
        var packedResponse = packer.toByteArray();
        packer.clear();

        @Cleanup var unpacker = MessagePack.newDefaultUnpacker(packedResponse);
        var unpackedResponseValue = unpacker.unpackValue().asArrayValue();
        var unpackedResponse = UnpackedResponse.fromValue(unpackedResponseValue);
        packer.packValue(ValueFactory.newBinary(unpackedResponse.getResponseData()));

        var respData = packer.toByteArray();
        packer.clear();

        Assertions.assertArrayEquals(responseData, unpackedResponse.getResponseData());
        Assertions.assertArrayEquals(requestId, unpackedResponse.getRequestId());
    }

}