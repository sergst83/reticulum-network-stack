package io.reticulum.link;

//import io.reticulum.destination.DestinationType;
//import io.reticulum.transport.TransportType;
import lombok.extern.slf4j.Slf4j;
//import io.reticulum.Reticulum;
//import static io.reticulum.link.TeardownSession.DESTINATION_CLOSED;
//import static io.reticulum.link.TeardownSession.INITIATOR_CLOSED;
//import static io.reticulum.link.TeardownSession.TIMEOUT;
import static io.reticulum.link.LinkStatus.ACTIVE;
import static org.apache.commons.codec.binary.Hex.encodeHexString;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import examples.LinkApp;
//import lombok.extern.slf4j.Slf4j;
import io.reticulum.Transport;

//import java.io.IOException;
//import java.nio.ByteBuffer;
//import java.util.function.Function;
import java.util.concurrent.TimeUnit;
//import static java.util.Objects.nonNull;
import static java.util.Objects.isNull;

//import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class LinkTest {

    LinkApp node1;
    LinkApp node2;
    LinkStatus linkStatus;

    @Test
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
                log.info("sleep interrupted: {}", e);
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

}