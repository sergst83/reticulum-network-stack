package io.reticulum.utils;

import io.reticulum.Transport;
import io.reticulum.packet.Packet;
import io.reticulum.resource.Resource;
import io.reticulum.resource.ResourceAdvertisement;
import io.reticulum.resource.ResourceStatus;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.function.Consumer;

import static io.reticulum.constant.ResourceConstant.SDU;
import static io.reticulum.constant.ResourceConstant.WINDOW;
import static io.reticulum.constant.ResourceConstant.WINDOW_FLEXIBILITY;
import static io.reticulum.constant.ResourceConstant.WINDOW_MAX;
import static io.reticulum.constant.ResourceConstant.WINDOW_MIN;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

@Slf4j
public class ResourceUtils {

    public static Resource accept(
            @NonNull Packet advertisement_packet,
            Consumer<Resource> callback,
            Consumer<Resource> progressCallback,
            byte[] requestId
    ) {
        try {
            var adv = ResourceAdvertisement.unpack(advertisement_packet.getPlaintext());

            var resource = new Resource(advertisement_packet.getLink(), requestId);
            resource.setStatus(ResourceStatus.TRANSFERRING);

            resource.setFlags(adv.getF());
            resource.setSize(adv.getT());
            resource.setTotalSize(adv.getD());
            resource.setUncompressedSize(adv.getD());
            resource.setHash(adv.getH());
            resource.setOriginalHash(adv.getO());
            resource.setRandomHash(adv.getR());
            resource.setHashmapRaw(adv.getM());
            resource.setEncrypted((adv.getF() & 0x01) == 0x01);
            resource.setCompressed(((adv.getF() >> 1) & 0x01) == 0x01);
            resource.setInitiator(false);
            resource.setCallback(callback);
            resource.setProgressCallback(progressCallback);
            resource.setTotalParts((int) Math.ceil(resource.getSize() / (double) SDU));
            resource.setReceivedCount(0);
            resource.setOutstandingParts(0);
            resource.setParts(new ArrayList<>(resource.getTotalParts()));
            resource.setWindow(WINDOW);
            resource.setWindowMax(WINDOW_MAX);
            resource.setWindowMin(WINDOW_MIN);
            resource.setWindowFlexibility(WINDOW_FLEXIBILITY);
            resource.setLastActivity(Instant.now());

            resource.setStoragePath(Path.of(Transport.getInstance().getOwner().getStoragePath().toString(), Hex.encodeHexString(resource.getOriginalHash())));
            resource.setSegmentIndex(adv.getI());
            resource.setTotalSegments(adv.getL());
            resource.setSplit(adv.getL() > 1);

            resource.setHashmap(new byte[resource.getTotalParts()]);
            resource.setHashmapHeight(0);
            resource.setWaitingForHmu(false);

            resource.setReceivingPart(false);

            resource.setConsecutiveCompletedHeight(0);

            if (isFalse(resource.getLink().hasIncomingResource(resource))) {
                resource.getLink().registerIncomingResource(resource);

                log.debug("Accepting resource advertisement for {}", Hex.encodeHexString(resource.getHash()));

                if (nonNull(resource.getLink().getCallbacks().getResourceStarted())) {
                    try {
                        resource.getLink().getCallbacks().getResourceStarted().accept(resource);
                    } catch (Exception e) {
                        log.error("Error while executing resource started callback from {}.", resource, e);
                    }
                }

                resource.hashmapUpdate(0, resource.getHashmapRaw());

                resource.watchdogJob();

                return resource;
            } else {
                log.debug("Ignoring resource advertisement for {}, resource already transferring", Hex.encodeHexString(resource.getHash()));

                return null;
            }
        } catch (Exception e) {
            log.debug("Could not decode resource advertisement, dropping resource");

            return null;
        }
    }
}
