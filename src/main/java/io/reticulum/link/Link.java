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
import java.util.function.Consumer;

import static io.reticulum.constant.IdentityConstant.SIGLENGTH;
import static io.reticulum.constant.LinkConstant.ECPUBSIZE;
import static io.reticulum.constant.LinkConstant.ESTABLISHMENT_TIMEOUT_PER_HOP;
import static io.reticulum.constant.LinkConstant.KEEPALIVE;
import static io.reticulum.constant.LinkConstant.KEEPALIVE_TIMEOUT_FACTOR;
import static io.reticulum.constant.LinkConstant.MDU;
import static io.reticulum.constant.LinkConstant.STALE_GRACE;
import static io.reticulum.constant.LinkConstant.STALE_TIME;
import static io.reticulum.constant.LinkConstant.TRAFFIC_TIMEOUT_FACTOR;
import static io.reticulum.constant.ResourceConstant.RESPONSE_MAX_GRACE_TIME;
import static io.reticulum.destination.DestinationType.LINK;
import static io.reticulum.destination.DestinationType.SINGLE;
import static io.reticulum.destination.Direction.IN;
import static io.reticulum.destination.RequestPolicy.ALLOW_ALL;
import static io.reticulum.destination.RequestPolicy.ALLOW_LIST;
import static io.reticulum.destination.RequestPolicy.ALLOW_NONE;
import static io.reticulum.link.ResourceStrategy.ACCEPT_NONE;
import static io.reticulum.link.Status.ACTIVE;
import static io.reticulum.link.Status.CLOSED;
import static io.reticulum.link.Status.PENDING;
import static io.reticulum.link.Status.STALE;
import static io.reticulum.link.TeardownSession.DESTINATION_CLOSED;
import static io.reticulum.link.TeardownSession.INITIATOR_CLOSED;
import static io.reticulum.link.TeardownSession.TIMEOUT;
import static io.reticulum.packet.PacketContextType.LINKCLOSE;
import static io.reticulum.packet.PacketContextType.LINKIDENTIFY;
import static io.reticulum.packet.PacketContextType.LRPROOF;
import static io.reticulum.packet.PacketContextType.LRRTT;
import static io.reticulum.packet.PacketContextType.REQUEST;
import static io.reticulum.packet.PacketContextType.RESPONSE;
import static io.reticulum.packet.PacketType.DATA;
import static io.reticulum.packet.PacketType.PROOF;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static io.reticulum.utils.IdentityUtils.truncatedHash;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.copyOfRange;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.concurrent.Executors.defaultThreadFactory;

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
    private volatile Status status = PENDING;
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
    private byte[] requestData;
    private Packet packet;
    private byte[] hash;
    private byte[] sharedKey;
    private byte[] derivedKey;
    private byte[] peerSigPubBytes;
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

        sigPub = sigPrv.generatePublicKey();
        sigPubBytes = sigPub.getEncoded();

        if (initiator) {
            requestData = concatArrays(pubBytes, sigPubBytes);
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

        this.sigPubBytes = peerSigPubBytes;
        this.sigPub = new Ed25519PublicKeyParameters(this.sigPubBytes);

//        if not hasattr(self.peer_pub, "curve"):
//            self.peer_pub.curve = Link.CURVE
    }

    public void setLinkId(Packet packet) {
        this.linkId = packet.getTruncatedHash();
        this.hash = this.linkId;
    }

    public synchronized void handshake() {
        this.status = Status.HANDSHAKE;

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
        var signedData = concatArrays(linkId, pubBytes, sigPubBytes);
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
                                // todo не используется - удалить, если так и не пригодится нигде или добавить
                                // response_resource = RNS.Resource(packed_response, self, request_id = request_id, is_response = True)
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

    private void requestResourceConcluded(@NonNull final Resource resource) throws IOException {
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

    private void responseResourceConcluded(@NonNull Resource resource) throws IOException {
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

    public synchronized void receive(Packet packet) {

    }

    public void establishmentTimeout(int timeout) {

    }

    public void addEstablishmentCost(int length) {
        establishmentCost.getAndAdd(length);
    }

    private byte[] sign(byte[] message) {
        var signer = new Ed25519Signer();
        signer.init(true, sigPrv);
        signer.update(message, 0, message.length);

        return signer.generateSignature();
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

    public void setLinkEstablishedCallback(Runnable establishedCallback) {
        callbacks.setLinkEstablished(establishedCallback);
    }

    public void setLinkClosedCallback(Consumer<Link> closedCallback) {
        callbacks.setLinkClosed(closedCallback);
    }
}
