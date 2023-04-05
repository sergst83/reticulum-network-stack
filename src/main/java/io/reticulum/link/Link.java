package io.reticulum.link;

import io.reticulum.Transport;
import io.reticulum.channel.Channel;
import io.reticulum.channel.LinkChannelOutlet;
import io.reticulum.cryptography.Fernet;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.PackedResponse;
import io.reticulum.destination.Request;
import io.reticulum.identity.Identity;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.packet.Packet;
import io.reticulum.packet.PacketContextType;
import io.reticulum.packet.PacketType;
import io.reticulum.resource.Resource;
import io.reticulum.resource.ResourceAdvertisement;
import io.reticulum.resource.ResourceStatus;
import io.reticulum.resource.ResourceStrategy;
import io.reticulum.utils.IdentityUtils;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.msgpack.core.MessagePack;
import org.msgpack.value.ValueFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.reticulum.constant.IdentityConstant.HASHLENGTH;
import static io.reticulum.constant.IdentityConstant.KEYSIZE;
import static io.reticulum.constant.IdentityConstant.SIGLENGTH;
import static io.reticulum.constant.LinkConstant.ECPUBSIZE;
import static io.reticulum.constant.LinkConstant.ESTABLISHMENT_TIMEOUT_PER_HOP;
import static io.reticulum.constant.LinkConstant.KEEPALIVE;
import static io.reticulum.constant.LinkConstant.KEEPALIVE_TIMEOUT_FACTOR;
import static io.reticulum.constant.LinkConstant.MDU;
import static io.reticulum.constant.LinkConstant.STALE_GRACE;
import static io.reticulum.constant.LinkConstant.STALE_TIME;
import static io.reticulum.constant.LinkConstant.TRAFFIC_TIMEOUT_FACTOR;
import static io.reticulum.constant.ResourceConstant.HASHMAP_IS_EXHAUSTED;
import static io.reticulum.constant.ResourceConstant.MAPHASH_LEN;
import static io.reticulum.constant.ResourceConstant.RESPONSE_MAX_GRACE_TIME;
import static io.reticulum.destination.DestinationType.LINK;
import static io.reticulum.destination.DestinationType.SINGLE;
import static io.reticulum.destination.Direction.IN;
import static io.reticulum.destination.ProofStrategy.PROVE_ALL;
import static io.reticulum.destination.ProofStrategy.PROVE_APP;
import static io.reticulum.destination.RequestPolicy.ALLOW_ALL;
import static io.reticulum.destination.RequestPolicy.ALLOW_LIST;
import static io.reticulum.destination.RequestPolicy.ALLOW_NONE;
import static io.reticulum.link.LinkStatus.ACTIVE;
import static io.reticulum.link.LinkStatus.CLOSED;
import static io.reticulum.link.LinkStatus.PENDING;
import static io.reticulum.link.LinkStatus.STALE;
import static io.reticulum.link.TeardownSession.DESTINATION_CLOSED;
import static io.reticulum.link.TeardownSession.INITIATOR_CLOSED;
import static io.reticulum.link.TeardownSession.TIMEOUT;
import static io.reticulum.packet.PacketContextType.CHANNEL;
import static io.reticulum.packet.PacketContextType.LINKCLOSE;
import static io.reticulum.packet.PacketContextType.LINKIDENTIFY;
import static io.reticulum.packet.PacketContextType.LRPROOF;
import static io.reticulum.packet.PacketContextType.LRRTT;
import static io.reticulum.packet.PacketContextType.REQUEST;
import static io.reticulum.packet.PacketContextType.RESOURCE;
import static io.reticulum.packet.PacketContextType.RESOURCE_ADV;
import static io.reticulum.packet.PacketContextType.RESOURCE_HMU;
import static io.reticulum.packet.PacketContextType.RESOURCE_ICL;
import static io.reticulum.packet.PacketContextType.RESOURCE_PRF;
import static io.reticulum.packet.PacketContextType.RESOURCE_REQ;
import static io.reticulum.packet.PacketContextType.RESPONSE;
import static io.reticulum.packet.PacketType.DATA;
import static io.reticulum.packet.PacketType.PROOF;
import static io.reticulum.resource.ResourceStrategy.ACCEPT_ALL;
import static io.reticulum.resource.ResourceStrategy.ACCEPT_APP;
import static io.reticulum.resource.ResourceStrategy.ACCEPT_NONE;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static io.reticulum.utils.IdentityUtils.truncatedHash;
import static java.math.BigInteger.ONE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.copyOfRange;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.concurrent.Executors.defaultThreadFactory;
import static org.apache.commons.lang3.ArrayUtils.getLength;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

@Slf4j
@Getter
@Setter
public class Link {

    private AtomicLong establishmentCost = new AtomicLong(0);
    private byte[] linkId;
    private ConnectionInterface attachedInterface;
    private Instant requestTime;

    private long rtt;
    private LinkCallbacks callbacks = new LinkCallbacks();
    private ResourceStrategy resourceStrategy = ACCEPT_NONE;
    private List<Resource> outgoingResources = new CopyOnWriteArrayList<>();
    private List<Resource> incomingResources = new CopyOnWriteArrayList<>();
    private List<RequestReceipt> pendingRequests = new CopyOnWriteArrayList<>();
    private Instant lastInbound;
    private Instant lastOutbound;
    private BigInteger tx = BigInteger.valueOf(0);
    private BigInteger rx = BigInteger.valueOf(0);
    private BigInteger txBytes = BigInteger.valueOf(0);
    private BigInteger rxBytes = BigInteger.valueOf(0);
    private int trafficTimeoutFactor = TRAFFIC_TIMEOUT_FACTOR;
    private int keepaliveTimeoutFactor = KEEPALIVE_TIMEOUT_FACTOR;
    private int keepalive = KEEPALIVE;
    private int staleTime = STALE_TIME;
    private ReentrantLock watchdogLock = new ReentrantLock();
    private volatile LinkStatus status = PENDING;
    private Instant activatedAt;
    private DestinationType type = LINK;
    private Destination owner;
    private Destination destination;
    private Identity remoteIdentity;
    private Channel channel;
    private boolean initiator;
    private X25519PrivateKeyParameters prv;
    private X25519PublicKeyParameters pub;
    private byte[] pubBytes;
    private Ed25519PrivateKeyParameters sigPrv;
    private Ed25519PublicKeyParameters sigPub;
    private byte[] sigPubBytes;
    /**
     * Timeout in seconds
     */
    private int establishmentTimeout;
    private Fernet fernet;
    private byte[] peerPubBytes;
    private X25519PublicKeyParameters peerPub;
    private byte[] peerSigPubBytes;
    private Ed25519PublicKeyParameters peerSigPub;
    private byte[] requestData;
    private Packet packet;
    private byte[] hash;
    private byte[] sharedKey;
    private byte[] derivedKey;
    private long establishmentRate;
    private TeardownSession teardownReason;

    @SneakyThrows
    private void init() {
        if (nonNull(destination) && destination.getType() != SINGLE) {
            throw new IllegalArgumentException("Links can only be established to the SINGLE destination type");
        }
        if (isNull(this.destination)) {
            initiator = false;
            prv = new X25519PrivateKeyParameters(new SecureRandom());
            sigPrv = owner.getIdentity().getSigPrv();
        } else {
            initiator = true;
            establishmentTimeout = ESTABLISHMENT_TIMEOUT_PER_HOP * Math.max(1, Transport.getInstance().hopsTo(destination.getHash()));
            prv = new X25519PrivateKeyParameters(new SecureRandom());
            sigPrv = new Ed25519PrivateKeyParameters(new SecureRandom());
        }

        pub = prv.generatePublicKey();
        pubBytes = pub.getEncoded();

        peerSigPub = sigPrv.generatePublicKey();
        peerSigPubBytes = peerSigPub.getEncoded();

        if (initiator) {
            requestData = concatArrays(pubBytes, peerSigPubBytes);
            packet = new Packet(destination, requestData, PacketType.LINKREQUEST);
            packet.pack();
            establishmentCost.getAndIncrement();
            setLinkId(packet);
            Transport.getInstance().registerLink(this);
            requestTime = Instant.now();
            startWatchdog();
            packet.send();
            hadOutbound();

            log.debug("Link request {}  sent to {}", linkId, destination);
        }
    }
    public Link(Destination destination) {
        this.destination = destination;
        init();
    }

    public Link(Destination owner, byte[] peerPubBytes, byte[] peerSigPubBytes) {
        this.owner = owner;
        loadPeer(peerPubBytes, peerSigPubBytes);
        init();
    }

    private void loadPeer(byte[] peerPubBytes, byte[] peerSigPubBytes) {
        this.peerPubBytes = peerPubBytes;
        this.peerPub = new X25519PublicKeyParameters(this.peerPubBytes);

        this.peerSigPubBytes = peerSigPubBytes;
        this.peerSigPub = new Ed25519PublicKeyParameters(this.peerSigPubBytes);

//        if not hasattr(self.peer_pub, "curve"):
//            self.peer_pub.curve = Link.CURVE
    }

    public void setLinkId(Packet packet) {
        this.linkId = packet.getTruncatedHash();
        this.hash = this.linkId;
    }

    public synchronized void handshake() {
        this.status = LinkStatus.HANDSHAKE;

        var agreement = new X25519Agreement();
        agreement.init(prv);
        var sharedKey = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(peerPub, sharedKey, 0);
        this.sharedKey = sharedKey;

        var hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(sharedKey, getSalt(), getContext()));
        var derivedKey = new byte[32];
        hkdf.generateBytes(derivedKey, 0, derivedKey.length);
        this.derivedKey = derivedKey;
    }

    @SneakyThrows
    public void prove() {
        var signedData = concatArrays(linkId, pubBytes, peerSigPubBytes);
        var signature = owner.getIdentity().sign(signedData);

        var proofData = concatArrays(signature, pubBytes);
        var proof = new Packet(this, proofData, PROOF, LRPROOF);
        proof.send();
        this.establishmentCost.getAndAdd(proof.getRaw().length);
        this.hadOutbound();
    }

    @SneakyThrows
    private void provePacket(Packet packet) {
        var signature = sign(packet.getPacketHash());
        // TODO: Hardcoded as explicit proof for now
        // if Reticulum.shouldUseImplicitProof():
        //   proofData = signature
        // else:
        //   proofData = packet.packetHash + signature
        var proofData = concatArrays(packet.getPacketHash(), signature);

        var proof = new Packet(this, proofData, PROOF);
        proof.send();
        hadOutbound();
    }

    @SneakyThrows
    private synchronized void validateProof(Packet packet) {
        if (status == PENDING) {
            if (initiator && packet.getData().length == (SIGLENGTH / 8 + ECPUBSIZE / 2)) {
                var peerPubBytes = copyOfRange(packet.getData(), SIGLENGTH / 8, SIGLENGTH / 8 + ECPUBSIZE / 2);
                var peerSigPubBytes = copyOfRange(destination.getIdentity().getPublicKey(), ECPUBSIZE / 2, ECPUBSIZE);
                loadPeer(peerPubBytes, peerSigPubBytes);
                handshake();

                establishmentCost.getAndAdd(packet.getRaw().length);
                var signedData = concatArrays(linkId, this.peerPubBytes, this.peerSigPubBytes);
                var signature = copyOfRange(packet.getData(), 0, SIGLENGTH / 8);

                if (destination.getIdentity().validate(signature, signedData)) {
                    this.rtt = Duration.between(requestTime, Instant.now()).toMillis();
                    this.attachedInterface = packet.getReceivingInterface();
                    this.remoteIdentity = this.destination.getIdentity();
                    this.status = ACTIVE;
                    this.activatedAt = Instant.now();
                    Transport.getInstance().activateLink(this);

                    log.info("Link {} established with {}, RTT is {} ms", this, destination, rtt);

                    if (rtt > 0 && establishmentCost.get() > 0) {
                        this.establishmentRate = this.establishmentCost.get() / rtt;
                    }

                    try (var packer = MessagePack.newDefaultBufferPacker()) {
                        packer.packLong(this.rtt);

                        var rttData = packer.toByteArray();
                        var rttPacket = new Packet(this, rttData, LRRTT);
                        rttPacket.send();

                        hadOutbound();
                    }

                    if (nonNull(callbacks.getLinkEstablished())) {
                        defaultThreadFactory().newThread(callbacks.getLinkEstablished()).start();
                    }
                } else {
                    log.debug("Invalid link proof signature received by {}. Ignoring.", this);
                }
            }
        }
    }

    /**
     * Identifies the initiator of the link to the remote peer. This can only happen
     * once the link has been established, and is carried out over the encrypted link.
     * The identity is only revealed to the remote peer, and initiator anonymity is
     * thus preserved. This method can be used for authentication.
     *
     * @param identity {@link Identity} to identify as.
     */
    @SneakyThrows
    public void identify(@NonNull Identity identity) {
        if (this.initiator) {
            var signedData = concatArrays(linkId, identity.getPublicKey());
            var signature = identity.sign(signedData);
            var proofData = concatArrays(identity.getPublicKey(), signature);

            var proof = new Packet(this, proofData, DATA, LINKIDENTIFY);
            proof.send();

            this.hadOutbound();
        }
    }

    /**
     * Sends a request to the remote peer.
     *
     * @param path The request path.
     * @param data
     * @param responseCallback An optional function or method with the signature
     * @param failedCallback An optional function or method with the signature to be called when a request fails.
     * @param progressCallback An optional function or method with the signature to be called when progress is made
     *                         receiving the response. Progress can be accessed as a float between 0.0 and 1.0 by the
     *                         *request_receipt.progress* property.
     * @param timeout An optional timeout in seconds for the request. If *None* is supplied it will be calculated based on link RTT.
     * @return A {@link RequestReceipt} instance if the request was sent. Or null if it was not.
     */
    @SneakyThrows
    public RequestReceipt request(
            String path,
            byte[] data,
            Consumer<RequestReceipt> responseCallback,
            Consumer<RequestReceipt> failedCallback,
            Consumer<RequestReceipt> progressCallback,
            Long timeout
    ) {
        byte[] requestPathHash = truncatedHash(path.getBytes(UTF_8));
        var unpackedRequest = new UnpackedRequest(Instant.now(), requestPathHash, data);
        byte[] packedRequest;
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            packer.packValue(unpackedRequest.toValue());
            packedRequest = packer.toByteArray();
        }

        long localTimeout = Optional.of(timeout)
                .orElse(this.rtt * this.trafficTimeoutFactor * RESPONSE_MAX_GRACE_TIME / 4);

        if (packedRequest.length < MDU) {
            var requestPacket = new Packet(this, packedRequest, DATA, REQUEST);
            var packetReceipt = requestPacket.send();

            if (isNull(packetReceipt)) {
                return null;
            } else {
                packetReceipt.setTimeout(localTimeout);

                return new RequestReceipt(
                        this,
                        packetReceipt,
                        responseCallback,
                        failedCallback,
                        progressCallback,
                        localTimeout,
                        packedRequest.length
                );
            }
        } else {
            var requestId = truncatedHash(packedRequest);
            log.debug("Sending request {} as resource.", requestId);
            var requestResource = new Resource(packedRequest, this, requestId, false, localTimeout);

            return new RequestReceipt(
                    this,
                    requestResource,
                    responseCallback,
                    failedCallback,
                    progressCallback,
                    localTimeout,
                    packedRequest.length
            );
        }
    }

    public synchronized void rttPacket(Packet packet) {
        try {
            var measuredRtt = Duration.between(requestTime, Instant.now()).toMillis();
            var plainText = decrypt(packet.getData());
            try (var unpacker = MessagePack.newDefaultUnpacker(plainText)) {
                rtt = Math.max(measuredRtt, unpacker.unpackLong());
            }

            activatedAt = Instant.now();

            if (rtt > 0 && establishmentCost.get() > 0) {
                establishmentRate = establishmentCost.get() / rtt;
            }

            try {
                if (nonNull(owner.getCallbacks().getLinkEstablished())) {
                    owner.getCallbacks().getLinkEstablished().accept(this);
                }
            } catch (Exception e) {
                log.error("Error occurred in external link establishment callback", e);
            }
        } catch (Exception e) {
            log.error("Error occurred while processing RTT packet, tearing down link.", e);
        }
    }

    /**
     * @return The data transfer rate at which the link establishment procedure ocurred, in bits per second.
     */
    public long getEstablishmentRate() {
        return establishmentRate * 8;
    }

    private byte[] getSalt() {
        return linkId;
    }

    private byte[] getContext() {
        return null;
    }

    /**
     * @return The time in milliseconds since last inbound packet on the link.
     */
    public long noInboundFor() {
        Instant time;
        if (nonNull(activatedAt) && activatedAt.compareTo(lastInbound) < 0) {
            time = activatedAt;
        } else {
            time = lastInbound;
        }

        return Duration.between(time, Instant.now()).toMillis();
    }

    /**
     * @return The time in milliseconds since last outbound packet on the link.
     */
    public long noOutboundFor() {
        return Duration.between(lastOutbound, Instant.now()).toMillis();
    }

    /**
     * @return The time in milliseconds since activity on the link.
     */
    public long inactiveFor() {
        return Math.min(noInboundFor(), noOutboundFor());
    }

    private synchronized void hadOutbound() {
        this.lastOutbound = Instant.now();
    }

    /**
     * Closes the link and purges encryption keys. New keys will
     * be used if a new link to the same destination is established.
     */
    public synchronized void teardown() {
        if (status != PENDING && status != CLOSED) {
            var teardownPacket = new Packet(this, linkId, LINKCLOSE);
            teardownPacket.send();
            hadOutbound();
        }
        status = CLOSED;
        if (initiator) {
            this.teardownReason = INITIATOR_CLOSED;
        } else {
            this.teardownReason = DESTINATION_CLOSED;
        }
        linkClosed();
    }

    private synchronized void teardownPacket(@NonNull Packet packet) {
        try {
            var plainText = decrypt(packet.getData());
            if (Arrays.equals(plainText, linkId)) {
                status = CLOSED;
                if (initiator) {
                    teardownReason = DESTINATION_CLOSED;
                } else {
                    teardownReason = INITIATOR_CLOSED;
                }
                linkClosed();
            }
        } catch (Exception ignore) {

        }
    }

    private synchronized void linkClosed() {
        incomingResources.forEach(Resource::cancel);
        outgoingResources.forEach(Resource::cancel);

        if (nonNull(channel)) {
            channel.shutdown();
        }

        prv = null;
        pub = null;
        pubBytes = null;
        sharedKey = null;
        derivedKey = null;

        if (nonNull(destination) && destination.getDirection() == IN) {
            destination.getLinks().remove(this);
        }

        if (nonNull(callbacks.getLinkClosed())) {
            try {
                callbacks.getLinkClosed().accept(this);
            } catch (Exception e) {
                log.error("Error while executing link closed callback from {}", this, e);
            }
        }
    }

    public void startWatchdog() {
        defaultThreadFactory().newThread(watchdogJob()).start();
    }

    private Runnable watchdogJob() {
        return () -> {
            while (status != CLOSED) {
                while (watchdogLock.isLocked()) {
                    try {
                        Thread.sleep(Math.max(rtt, 25));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }

                var sleepTime = 0L;
                var nextCheck = Instant.now();
                if (status != CLOSED) {
                    // Link was initiated, but no response from destination yet
                    switch (status) {
                        case PENDING:
                            nextCheck = this.requestTime.plusSeconds(this.establishmentTimeout);
                            sleepTime = Duration.between(Instant.now(), nextCheck).toMillis();
                            if (Instant.now().compareTo(nextCheck) >= 0) {
                                log.info("Link establishment timed out");
                                status = CLOSED;
                                teardownReason = TIMEOUT;
                                linkClosed();
                                sleepTime = 1;
                            }
                            break;
                        case HANDSHAKE:
                            nextCheck = this.requestTime.plusSeconds(this.establishmentTimeout);
                            sleepTime = Duration.between(Instant.now(), nextCheck).toMillis();
                            if (Instant.now().compareTo(nextCheck) >= 0) {
                                if (initiator) {
                                    log.debug("Timeout waiting for link request proof");
                                } else {
                                    log.debug("Timeout waiting for RTT packet from link initiator");
                                }
                                status = CLOSED;
                                teardownReason = TIMEOUT;
                                linkClosed();
                                sleepTime = 1;
                            }
                            break;
                        case ACTIVE:
                            Instant time;
                            if (nonNull(activatedAt) && activatedAt.compareTo(lastInbound) < 0) {
                                time = activatedAt;
                            } else {
                                time = lastInbound;
                            }
                            var now = Instant.now();
                            if (now.compareTo(time.plusSeconds(keepalive)) <= 0) {
                                if (initiator) {
                                    sendKeepalive();
                                }

                                if (now.compareTo(time.plusSeconds(staleTime)) <= 0) {
                                    sleepTime =  + Duration.ofSeconds(STALE_GRACE).plusMillis(rtt * keepaliveTimeoutFactor).toMillis();
                                    status = STALE;
                                } else {
                                    sleepTime = Duration.ofSeconds(keepalive).toMillis();
                                }
                            } else {
                                sleepTime = Duration.between(now, time.plusSeconds(keepalive)).toMillis();
                            }
                            break;
                        case STALE:
                            sleepTime = 1;
                            status = CLOSED;
                            teardownReason = TIMEOUT;
                            linkClosed();
                            break;
                    }

                    if (sleepTime == 0) {
                        log.error("Warning! Link watchdog sleep time of 0!");
                    } else if (sleepTime <= 0) {
                        log.error("Timing error! Tearing down link {}  now.", this);
                        teardown();
                        sleepTime = 100;
                    }

                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        };
    }

    private void sendKeepalive() {
        var keepalivePacket = new Packet(this, new byte[]{(byte) 0xFF}, PacketContextType.KEEPALIVE);
        keepalivePacket.send();
        hadOutbound();
    }

    private void handleRequest(byte[] requestId, @NonNull UnpackedRequest uppackedRequest) throws IOException {
        if (status == ACTIVE) {
            var requestedAt = uppackedRequest.getTime();
            var pathHash = uppackedRequest.getRequestPathHash();
            var requestData = uppackedRequest.getData();

            var pathHashHex = Hex.encodeHexString(pathHash);
            if (destination.getRequestHandlers().containsKey(pathHashHex)) {
                var requestHandler = destination.getRequestHandlers().get(pathHashHex);
                var path = requestHandler.getPath();
                var responseGenerator = requestHandler.getResponseGenerator();
                var allow = requestHandler.getAllow();
                var allowedList = requestHandler.getAllowedList();

                var allowed = false;
                if (allow != ALLOW_NONE) {
                    if (allow == ALLOW_LIST) {
                        if (nonNull(remoteIdentity) && allowedList.stream().anyMatch(a -> Arrays.equals(remoteIdentity.getHash(), a))) {
                            allowed = true;
                        }
                    } else if (allow == ALLOW_ALL) {
                        allowed = true;
                    }
                }

                if (allowed) {
                    log.debug("Handling request {}  for: {}", Hex.encodeHexString(requestId), path);
                    var response = responseGenerator.apply(new Request(path, requestData, requestId, linkId, remoteIdentity, requestedAt));
                    if (nonNull(response)) {
                        try (var packer = MessagePack.newDefaultBufferPacker()) {
                            packer.packValue(new PackedResponse(requestId, response).toValue());
                            var packedResponse = packer.toByteArray();

                            if (packedResponse.length <= MDU) {
                                new Packet(this, packedResponse, DATA, RESPONSE).send();
                            } else {
                                 var responseResource = new Resource(packedResponse, this, requestId, true);
                            }
                        }
                    }
                } else {
                    var identityString = Optional.ofNullable(getRemoteIdentity())
                            .map(Identity::toString)
                            .orElse("<Unknown>");
                    log.debug("Request {}  from {} not allowed for: {}", Hex.encodeHexString(requestId), identityString, path);
                }
            }
        }
    }

    private void handleResponse(byte[] requestId, byte[] responseData, int responseSize, int responseTransferSize) {
        if (status == ACTIVE) {
            pendingRequests.stream()
                    .filter(pendingRequest -> Arrays.equals(pendingRequest.getRequestId(), requestId))
                    .findFirst()
                    .flatMap(
                            pendingRequest -> {
                                try {
                                    pendingRequest.setResponseSize(responseSize);
                                    pendingRequest.setResponseTransferSize(responseTransferSize);
                                    pendingRequest.responseReceived(responseData);
                                } catch (Exception e) {
                                    log.error("Error occurred while handling response.", e);
                                }

                                return Optional.of(pendingRequest);
                            }
                    ).ifPresent(pendingRequests::remove);
        }
    }

    @SneakyThrows
    private void requestResourceConcluded(@NonNull final Resource resource) {
        if (resource.getStatus() == ResourceStatus.COMPLETE) {
            var packedRequest = resource.getData().readAllBytes();
            try (var unpacker = MessagePack.newDefaultUnpacker(packedRequest)) {
                var unpackedRequestValue = unpacker.unpackValue().asArrayValue();
                var requestId = IdentityUtils.truncatedHash(packedRequest);
                handleRequest(requestId, UnpackedRequest.fromValue(unpackedRequestValue));
            }
        } else {
            log.debug("Incoming request resource failed with status: {}", resource.getStatus());
        }
    }

    @SneakyThrows
    private void responseResourceConcluded(@NonNull Resource resource) {
        if (resource.getStatus() == ResourceStatus.COMPLETE) {
            try (var unpacker = MessagePack.newDefaultUnpacker(resource.getData())) {
                var unpackedResponseValue = unpacker.unpackValue().asArrayValue();
                var unpackedResponse = UnpackedResponse.fromValue(unpackedResponseValue);
                handleResponse(unpackedResponse.getRequestId(), unpackedResponse.getResponseData(), resource.getTotalSize(), resource.getSize());
            }
        } else {
            log.debug("Incoming response resource failed with status: {}", resource.getStatus());
            pendingRequests.stream()
                    .filter(pendingRequest -> Arrays.equals(pendingRequest.getRequestId(), resource.getRequestId()))
                    .findFirst()
                    .ifPresent(pendingRequest -> pendingRequest.requestTimedOut(null));
        }
    }

    /**
     * @return {@link Channel} for this link.
     */
    public synchronized Channel getChannel() {
        if (isNull(channel)) {
            channel = new Channel(new LinkChannelOutlet(this));
        }

        return channel;
    }

    @SneakyThrows
    public synchronized void receive(Packet packet) {
        watchdogLock.lock();
        if (status == CLOSED
                && isFalse(
                initiator && packet.getContext() == PacketContextType.KEEPALIVE
                        && Arrays.equals(packet.getData(), new byte[]{(byte) 0xFF}
                )
        )
        ) {
            if (isFalse(packet.getReceivingInterface().equals(attachedInterface))) {
                log.error("Link-associated packet received on unexpected interface! Someone might be trying to manipulate your communication!");
            } else {
                lastOutbound = Instant.now();
                rx = rx.add(ONE);
                rxBytes = rxBytes.add(BigInteger.valueOf(packet.getData().length));
                if (status == STALE) {
                    status = ACTIVE;
                }

                if (packet.getPacketType() == DATA) {
                    if (packet.getContext() == PacketContextType.NONE) {
                        var plainText = decrypt(packet.getData());
                        if (nonNull(callbacks.getPacket())) {
                            defaultThreadFactory()
                                    .newThread(() -> callbacks.getPacket().accept(plainText, packet))
                                    .start();
                        }
                        if (destination.getProofStrategy() == PROVE_ALL) {
                            packet.prove();
                        } else if (destination.getProofStrategy() == PROVE_APP) {
                            if (nonNull(destination.getCallbacks().getProofRequested())) {
                                try {
                                    destination.getCallbacks().getProofRequested().accept(packet);
                                } catch (Exception e) {
                                    log.error("Error while executing proof request callback from {}.", this, e);
                                }
                            }
                        }
                    } else if (packet.getContext() == LINKIDENTIFY) {
                        var plaintext = decrypt(packet.getData());

                        if (isFalse(initiator) && getLength(plaintext) == KEYSIZE / 8 + SIGLENGTH) {
                            var publicKey = copyOfRange(plaintext, 0, KEYSIZE / 8);
                            var signedData = concatArrays(linkId, publicKey);
                            var signature = copyOfRange(plaintext, KEYSIZE / 8, KEYSIZE / 8 + SIGLENGTH / 8);
                            var identity = new Identity(false);
                            identity.loadPublicKey(publicKey);

                            if (identity.validate(signature, signedData)) {
                                remoteIdentity = identity;
                                if (nonNull(callbacks.remoteIdentified)) {
                                    try {
                                        callbacks.getRemoteIdentified().accept(this, remoteIdentity);
                                    } catch (Exception e) {
                                        log.error("Error while executing remote identified callback from {}.", this, e);
                                    }
                                }
                            }
                        }
                    } else if (packet.getContext() == REQUEST) {
                        try {
                            var requestId = packet.getTruncatedHash();
                            var packetRequest = decrypt(packet.getData());
                            try (var unpacker = MessagePack.newDefaultUnpacker(packetRequest)) {
                                var unpackedRequestValue = unpacker.unpackValue().asArrayValue();
                                handleRequest(requestId, UnpackedRequest.fromValue(unpackedRequestValue));
                            }
                        } catch (Exception e) {
                            log.error("Error occurred while handling request", e);
                        }
                    } else if (packet.getContext() == RESPONSE) {
                        try {
                            var packedResponse = decrypt(packet.getData());
                            try (
                                    var unpacker = MessagePack.newDefaultUnpacker(packedResponse);
                                    var packer = MessagePack.newDefaultBufferPacker();
                            ) {
                                var unpackedResponseValue = unpacker.unpackValue().asArrayValue();
                                var unpackedResponse = UnpackedResponse.fromValue(unpackedResponseValue);
                                packer.packValue(ValueFactory.newBinary(unpackedResponse.getResponseData()));
                                var transferSize = getLength(packer.toByteArray()) - 2;
                                handleResponse(
                                        unpackedResponse.getRequestId(),
                                        unpackedResponse.getResponseData(),
                                        transferSize,
                                        transferSize
                                );
                            }
                        } catch (Exception e) {
                            log.error("Error occurred while handling response.", e);
                        }
                    } else if (packet.getContext() == LRRTT) {
                        if (isFalse(initiator)) {
                            rttPacket(packet);
                        }
                    } else if (packet.getContext() == LINKCLOSE) {
                        teardownPacket(packet);
                    } else if (packet.getContext() == RESOURCE_ADV) {
                        packet.setPlaintext(decrypt(packet.getData()));

                        if (ResourceAdvertisement.isRequest(packet)) {
                            Resource.accept(packet, this::requestResourceConcluded);
                        } else if (ResourceAdvertisement.isResponse(packet)) {
                            var requestId = ResourceAdvertisement.readRequestId(packet);
                            for (RequestReceipt pendingRequest : pendingRequests) {
                                if (Arrays.equals(pendingRequest.getRequestId(), requestId)) {
                                    Resource.accept(packet, this::responseResourceConcluded, pendingRequest::responseResourceProgress, requestId);
                                    pendingRequest.setResponseSize(ResourceAdvertisement.readSize(packet));
                                    pendingRequest.setResponseTransferSize(ResourceAdvertisement.readTransferSize(packet));
                                    pendingRequest.setStartedAt(Instant.now());
                                }
                            }
                        } else if (resourceStrategy == ACCEPT_NONE) {
                            // pass
                        } else if (resourceStrategy == ACCEPT_APP) {
                            if (nonNull(callbacks.getResource())) {
                                try {
                                    var resourceAdvertisement = ResourceAdvertisement.unpack(packet.getPlaintext());
                                    resourceAdvertisement.setLink(this);
                                    if (callbacks.getResource().apply(resourceAdvertisement)) {
                                        Resource.accept(packet, callbacks.getResourceConcluded());
                                    }
                                } catch (Exception e) {
                                    log.error("Error while executing resource accept callback from {}.", this, e);
                                }
                            }
                        } else if (resourceStrategy == ACCEPT_ALL) {
                            Resource.accept(packet, callbacks.getResourceConcluded());
                        }
                    } else if (packet.getContext() == RESOURCE_REQ) {
                        var plaintext = decrypt(packet.getData());
                        byte[] resourceHash;
                        if (nonNull(plaintext) && new String(plaintext).codePointAt(0) == HASHMAP_IS_EXHAUSTED) {
                            resourceHash = copyOfRange(plaintext, 1 + MAPHASH_LEN, HASHLENGTH / 8 + 1 + MAPHASH_LEN);
                        } else {
                            resourceHash = copyOfRange(plaintext, 1, HASHLENGTH / 8 + 1);
                        }

                        for (Resource resource : outgoingResources) {
                            if (Arrays.equals(resource.getHash(), resourceHash)) {
                                // We need to check that this request has not been
                                // received before in order to avoid sequencing errors.
                                if (resource.getReqHashList().stream().noneMatch(reqHash -> Arrays.equals(reqHash, packet.getPacketHash()))) {
                                    resource.getReqHashList().add(packet.getPacketHash());
                                    resource.request(plaintext);
                                }
                            }
                        }
                    } else if (packet.getContext() == RESOURCE_HMU) {
                        var plaintext = decrypt(packet.getData());
                        var resourceHash = copyOfRange(plaintext, 0, HASHLENGTH / 8);
                        for (Resource resource : incomingResources) {
                            if (Arrays.equals(resourceHash, resource.getHash())) {
                                resource.hashmapUpdatePacket(plaintext);
                            }
                        }
                    } else if (packet.getContext() == RESOURCE_ICL) {
                        var plaintext = decrypt(packet.getData());
                        var resourceHash = copyOfRange(plaintext, 0, HASHLENGTH / 8);
                        for (Resource resource : incomingResources) {
                            if (Arrays.equals(resourceHash, resource.getHash())) {
                                resource.cancel();
                            }
                        }
                    } else if (packet.getContext() == PacketContextType.KEEPALIVE) {
                        if (isFalse(initiator) && Arrays.equals(packet.getData(), new byte[] {(byte) 0xFF})) {
                            var keepalivePacket = new Packet(this, new byte[] {(byte) 0xFF}, PacketContextType.KEEPALIVE);
                            keepalivePacket.send();
                            hadOutbound();
                        }
                    }
                    // TODO: find the most efficient way to allow multiple
                    // transfers at the same time, sending resource hash on
                    // each packet is a huge overhead. Probably some kind
                    // of hash -> sequence map
                    else if (packet.getContext() == RESOURCE) {
                        for (Resource resource : incomingResources) {
                            resource.receivePart(packet);
                        }
                    } else if (packet.getContext() == CHANNEL) {
                        if (isNull(channel)) {
                            log.debug("Channel data received without open channel.");
                        } else {
                            packet.prove();
                            var plaintext = decrypt(packet.getData());
                            channel.receive(plaintext);
                        }
                    }
                } else if (packet.getPacketType() == PROOF) {
                    if (packet.getContext() == RESOURCE_PRF) {
                        var resourceHash = copyOfRange(packet.getData(), 0, HASHLENGTH / 8);
                        for (Resource resource : outgoingResources) {
                            if (Arrays.equals(resource.getHash(), resourceHash)) {
                                resource.validateProof(packet.getData());
                            }
                        }
                    }
                }
            }
        }
        watchdogLock.unlock();
    }

    private byte[] encrypt(@NonNull final byte[] plaintext) throws IOException {
        try {
            if (isNull(fernet)) {
                try {
                    fernet = new Fernet(derivedKey);
                } catch (Exception e) {
                    log.error("Could not {}  instantiate Fernet while performin encryption on link.", this, e);
                    throw e;
                }
            }

            return fernet.encrypt(plaintext);
        } catch (IOException e) {
            log.error("Encryption on link {} failed.", this, e);
            throw e;
        }
    }

    private byte[] decrypt(byte[] data) {
        try {
            if (isNull(fernet)) {
                fernet = new Fernet(derivedKey);

                return fernet.decrypt(data);
            }
        } catch (Exception e) {
            log.error("Decryption failed on link {}", this, e);
        }

        return null;
    }

    public byte[] sign(byte[] message) {
        var signer = new Ed25519Signer();
        signer.init(true, sigPrv);
        signer.update(message, 0, message.length);

        return signer.generateSignature();
    }

    public boolean validate(byte[] signature, byte[] message) {
        try {
            var verifier = new Ed25519Signer();
            verifier.init(false, peerSigPub);
            verifier.update(message, 0, message.length);

            return verifier.verifySignature(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public void setLinkEstablishedCallback(Runnable establishedCallback) {
        callbacks.setLinkEstablished(establishedCallback);
    }

    /**
     * Registers a function to be called when a link has been torn down.
     *
     * @param closedCallback
     */
    public void setLinkClosedCallback(Consumer<Link> closedCallback) {
        callbacks.setLinkClosed(closedCallback);
    }

    /**
     * Registers a function to be called when a packet has been received over this link.
     *
     * @param callback
     */
    public void setPacketCallback(BiConsumer<byte[], Packet> callback) {
        callbacks.setPacket(callback);
    }

    /**
     * Registers a function to be called when a resource has been advertised over this link. If the function returns
     * <strong>true</strong> the resource will be accepted. If it returns <strong>false</strong> it will be ignored.
     *
     * @param callback
     */
    public void setResourceCallback(Function<ResourceAdvertisement, Boolean> callback) {
        callbacks.setResource(callback);
    }

    /**
     * Registers a function to be called when a resource has begun transferring over this link.
     *
     * @param callback
     */
    public void setResourceStartedCallback(Consumer<Resource> callback) {
        callbacks.setResourceStarted(callback);
    }

    /**
     * Registers a function to be called when a resource has concluded transferring over this link.
     *
     * @param callback
     */
    public void setResourceConcludedCallback(Consumer<Resource> callback) {
        callbacks.setResourceConcluded(callback);
    }

    /**
     * Registers a function to be called when an initiating peer has identified over this link.
     *
     * @param callback
     */
    public void setRemoteIdentifiedCallback(BiConsumer<Link, Identity> callback) {
        callbacks.setRemoteIdentified(callback);
    }

    public void resourceConcluded(Resource resource) {
        incomingResources.remove(resource);
        outgoingResources.remove(resource);
    }

    public void registerOutgoingResource(@NonNull Resource resource) {
        outgoingResources.add(resource);
    }

    public void registerIncomingResource(@NonNull Resource resource) {
        incomingResources.add(resource);
    }

    public boolean hasIncomingResource(@NonNull Resource resource) {
        return incomingResources.contains(resource);
    }

    public void cancelOutgoingResource(@NonNull Resource resource) {
        if (isFalse(outgoingResources.remove(resource))) {
            log.error("Attempt to cancel a non-existing outgoing resource");
        }
    }

    public void cancelIncomingResource(@NonNull Resource resource) {
        if (isFalse(incomingResources.remove(resource))) {
            log.error("Attempt to cancel a non-existing incoming resource");
        }
    }

    public boolean readyForNewResource() {
        return outgoingResources.isEmpty();
    }

    public void addEstablishmentCost(int length) {
        establishmentCost.getAndAdd(length);
    }
}
