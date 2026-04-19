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
import static io.reticulum.constant.LinkConstant.LINK_MTU_SIZE;
import static org.apache.commons.lang3.ArrayUtils.subarray;

@Slf4j
public class LinkUtils {
    public static Link validateRequest(Destination owner, byte[] data, Packet packet) {
        final int baseLen = ECPUBSIZE;
        final int extLen  = ECPUBSIZE + LINK_MTU_SIZE;

        if (data.length == baseLen || data.length == extLen) {
            try {
                var link = new Link(owner,
                        subarray(data, 0, ECPUBSIZE / 2),
                        subarray(data, ECPUBSIZE / 2, ECPUBSIZE));

                // Parse signalling bytes from extended link request
                if (data.length == extLen) {
                    var parsedMtu = Link.mtuFromLrPacket(data);
                    if (parsedMtu != null) {
                        link.setMtu(parsedMtu);
                    }
                    link.setMode(Link.modeFromLrPacket(data));
                }

                // Compute link ID stripping signalling bytes (mirrors Python link_id_from_lr_packet)
                var linkId = linkIdFromLrPacket(packet);
                link.setLinkId(linkId);
                link.setHash(linkId);

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

                log.info("Incoming link request {} accepted", link);

                return link;
            } catch (Exception e) {
                log.error("Validating link request failed", e);
            }
        } else {
            log.error("Invalid link request payload size {}, dropping request", data.length);
        }

        return null;
    }

    /**
     * Compute the link ID (truncated hash) from a LINKREQUEST packet,
     * stripping any MTU signalling bytes before hashing — mirrors Python's
     * {@code Link.link_id_from_lr_packet(packet)}.
     */
    public static byte[] linkIdFromLrPacket(Packet packet) {
        var hashablePart = packet.getHashablePart();
        if (packet.getData().length > ECPUBSIZE) {
            var diff = packet.getData().length - ECPUBSIZE;
            // Strip the last `diff` bytes (the signalling bytes) from the hashable part
            hashablePart = subarray(hashablePart, 0, hashablePart.length - diff);
        }
        return IdentityUtils.truncatedHash(hashablePart);
    }
}
