package io.reticulum.utils;

import io.reticulum.Transport;
import io.reticulum.destination.Destination;
import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

import static io.reticulum.constant.LinkConstant.ECPUBSIZE;
import static io.reticulum.constant.LinkConstant.ESTABLISHMENT_TIMEOUT_PER_HOP;
import static io.reticulum.constant.LinkConstant.KEEPALIVE;
import static org.apache.commons.lang3.ArrayUtils.subarray;

@Slf4j
public class LinkUtils {
    public static Link validateRequest(Destination owner, byte[] data, Packet packet) {
        if (data.length == ECPUBSIZE) {
            try {
                var link = new Link(owner, subarray(data, 0, ECPUBSIZE / 2), subarray(data, ECPUBSIZE / 2, ECPUBSIZE));
                link.setLinkId(packet);
                link.setDestination((Destination) packet.getDestination());
                link.setEstablishmentTimeout(ESTABLISHMENT_TIMEOUT_PER_HOP + Math.max(1, packet.getHops()) + KEEPALIVE * 1_000);
                link.addEstablishmentCost(packet.getRaw().length);
                log.debug("Establishment timeout is {} ms for incoming link request {}", link.getEstablishmentTimeout(), link.getLinkId());
                link.handshake();
                link.setAttachedInterface(packet.getReceivingInterface());
                link.prove();
                link.setRequestTime(Instant.now());
                Transport.getInstance().registerLink(link);
                link.setLastInbound(Instant.now());
                link.startWatchdog();

                log.info("Incoming link request {}  accepted", link);

                return link;
            } catch (Exception e) {
                log.error("Validating link request failed", e);
            }
        } else {
            log.error("Invalid link request payload size, dropping request");
        }

        return null;
    }
}
