package io.reticulum.link;

import io.reticulum.packet.PacketReceipt;
import io.reticulum.packet.PacketReceiptStatus;
import io.reticulum.resource.Resource;
import io.reticulum.resource.ResourceStatus;
import lombok.Data;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import static io.reticulum.link.RequestReceiptStatus.DELIVERED;
import static io.reticulum.link.RequestReceiptStatus.FAILED;
import static io.reticulum.link.RequestReceiptStatus.READY;
import static io.reticulum.link.RequestReceiptStatus.RECEIVING;
import static io.reticulum.link.RequestReceiptStatus.SENT;
import static java.util.Objects.nonNull;
import static java.util.concurrent.Executors.defaultThreadFactory;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

/**
 * An instance of this class is returned by the <strong>request</strong> method of {@link Link}
 * instances. It should never be instantiated manually. It provides methods to
 * check status, response time and response data when the request concludes.
 */
@Data
@Slf4j
public class RequestReceipt {
    private byte[] hash;
    private Link link;
    private byte[] requestId;
    private int responseSize;
    private int responseTransferSize;
    private Instant startedAt = Instant.now();
    private PacketReceipt packetReceipt;
    private RequestReceiptStatus status = SENT;
    private Instant concludedAt;
    private Instant responseConcludedAt;
    private RequestReceiptCallbacks callbacks = new RequestReceiptCallbacks();
    private double progress = 0;
    private long timeout;
    private Instant resourceResponseTimeout;
    private byte[] response;

    private void init(
            Link link,
            Consumer<RequestReceipt> responseCallback,
            Consumer<RequestReceipt> failedCallback,
            Consumer<RequestReceipt> progressCallback,
            long timeout,
            int requestSize
    ) {
        this.link = link;
        this.requestId = this.hash;
        this.responseSize = requestSize;

        this.timeout = timeout;

        callbacks.setResponse(responseCallback);
        callbacks.setFailed(failedCallback);
        callbacks.setProgress(progressCallback);

        link.getPendingRequests().add(this);
    }

    public RequestReceipt(
            Link link,
            PacketReceipt packetReceipt,
            Consumer<RequestReceipt> responseCallback,
            Consumer<RequestReceipt> failedCallback,
            Consumer<RequestReceipt> progressCallback,
            long timeout,
            int requestSize
    ) {
        this.packetReceipt = packetReceipt;
        this.hash = packetReceipt.getTruncatedHash();
        this.packetReceipt.setTimeoutCallback(this::requestTimedOut);
        this.startedAt = Instant.now();

        init(link, responseCallback, failedCallback, progressCallback, timeout, requestSize);
    }

    public RequestReceipt(
            Link link,
            Resource requestResource,
            Consumer<RequestReceipt> responseCallback,
            Consumer<RequestReceipt> failedCallback,
            Consumer<RequestReceipt> progressCallback,
            long timeout,
            int requestSize
    ) {
        this.hash = requestResource.getRequestId();
        requestResource.setCallback(this::requestResourceConcluded);

        init(link, responseCallback, failedCallback, progressCallback, timeout, requestSize);
    }

    public synchronized void requestResourceConcluded(@NonNull Resource resource) {
        if (resource.getStatus() == ResourceStatus.COMPLETE) {
            log.debug("Request {} successfully sent as resource.", Hex.encodeHexString(requestId));
            startedAt = Instant.now();
            status = DELIVERED;
            resourceResponseTimeout = Instant.now().plusMillis(timeout);
            defaultThreadFactory().newThread(this::responseTimeoutJob).start();
        } else {
            log.debug("Sending request {}  as resource failed with status: {}", Hex.encodeHexString(requestId), resource.getStatus());
            status = FAILED;
            concludedAt = Instant.now();
            link.getPendingRequests().remove(this);

            if (nonNull(callbacks.getFailed())) {
                try {
                    callbacks.getFailed().accept(this);
                } catch (Exception e) {
                    log.error("Error while executing request failed callback from {}", this, e);
                }
            }
        }
    }

    @SneakyThrows
    private void responseTimeoutJob() {
        while (status == DELIVERED) {
            if (Instant.now().isAfter(resourceResponseTimeout)) {
                requestTimedOut(null);
            }

            Thread.sleep(100);
        }
    }

    public synchronized void requestTimedOut(PacketReceipt packetReceipt) {
        this.status = FAILED;
        this.concludedAt = Instant.now();
        this.link.getPendingRequests().remove(this);

        if (nonNull(callbacks.getFailed())) {
            try {
                callbacks.getFailed().accept(this);
            } catch (Exception e) {
                log.error("Error while executing request timed out callback from {}.", this, e);
            }
        }
    }

    public synchronized void responseResourceProgress(@NonNull Resource resource) {
        if (isFalse(status == FAILED)) {
            status = RECEIVING;
            if (nonNull(packetReceipt)) {
                if (packetReceipt.getStatus() != PacketReceiptStatus.DELIVERED) {
                    packetReceipt.setStatus(PacketReceiptStatus.DELIVERED);
                    packetReceipt.setProved(true);
                    packetReceipt.setConcludedAt(Instant.now());
                    if (nonNull(packetReceipt.getCallbacks().getDelivery())) {
                        packetReceipt.getCallbacks().getDelivery().accept(packetReceipt);
                    }
                }
            }

            progress = resource.getProgress();

            if (nonNull(callbacks.getProgress())) {
                try {
                    callbacks.getProgress().accept(this);
                } catch (Exception e) {
                    log.error("Error while executing response progress callback from {}.", this, e);
                }
            }
        } else {
            resource.cancel();
        }
    }

    public synchronized void responseReceived(byte[] responseData) {
        if (isFalse(status == FAILED)) {
            progress = 1.0;
            response = responseData;
            status = READY;
            responseConcludedAt = Instant.now();

            if (nonNull(packetReceipt)) {
                packetReceipt.setStatus(PacketReceiptStatus.DELIVERED);
                packetReceipt.setProved(true);
                packetReceipt.setConcludedAt(Instant.now());
                if (nonNull(packetReceipt.getCallbacks().getDelivery())) {
                    packetReceipt.getCallbacks().getDelivery().accept(packetReceipt);
                }
            }

            if (nonNull(callbacks.getProgress())) {
                try {
                    callbacks.getProgress().accept(this);
                } catch (Exception e) {
                    log.error("Error while executing response progress callback from {}.", this, e);
                }
            }

            if (nonNull(callbacks.getResponse())) {
                try {
                    callbacks.getResponse().accept(this);
                } catch (Exception e) {
                    log.error("Error while executing response received callback from {}.", this, e);
                }
            }
        }
    }

    public byte[] getResponse() {
        if (status == READY) {
            return response;
        } else {
            return null;
        }
    }

    /**
     * @return The response time of the request in milliseconds.
     */
    public Long getResponseTime() {
        if (status == READY) {
            return Duration.between(startedAt, responseConcludedAt).toMillis();
        } else {
            return null;
        }
    }
}
