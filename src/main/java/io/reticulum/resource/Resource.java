package io.reticulum.resource;

import io.reticulum.Transport;
import io.reticulum.constant.ResourceConstant;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;
import io.reticulum.packet.Packet;
import io.reticulum.utils.IdentityUtils;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.lang3.ArrayUtils;
import org.msgpack.core.MessagePack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static io.reticulum.constant.IdentityConstant.HASHLENGTH;
import static io.reticulum.constant.ResourceConstant.AUTO_COMPRESS_MAX_SIZE;
import static io.reticulum.constant.ResourceConstant.COLLISION_GUARD_SIZE;
import static io.reticulum.constant.ResourceConstant.FAST_RATE_THRESHOLD;
import static io.reticulum.constant.ResourceConstant.HASHMAP_IS_EXHAUSTED;
import static io.reticulum.constant.ResourceConstant.HASHMAP_IS_NOT_EXHAUSTED;
import static io.reticulum.constant.ResourceConstant.HASHMAP_MAX_LEN;
import static io.reticulum.constant.ResourceConstant.MAPHASH_LEN;
import static io.reticulum.constant.ResourceConstant.MAX_ADV_RETRIES;
import static io.reticulum.constant.ResourceConstant.MAX_EFFICIENT_SIZE;
import static io.reticulum.constant.ResourceConstant.MAX_RETRIES;
import static io.reticulum.constant.ResourceConstant.PART_TIMEOUT_FACTOR;
import static io.reticulum.constant.ResourceConstant.PART_TIMEOUT_FACTOR_AFTER_RTT;
import static io.reticulum.constant.ResourceConstant.PER_RETRY_DELAY;
import static io.reticulum.constant.ResourceConstant.RANDOM_HASH_SIZE;
import static io.reticulum.constant.ResourceConstant.RATE_FAST;
import static io.reticulum.constant.ResourceConstant.RETRY_GRACE_TIME;
import static io.reticulum.constant.ResourceConstant.SDU;
import static io.reticulum.constant.ResourceConstant.SENDER_GRACE_TIME;
import static io.reticulum.constant.ResourceConstant.WATCHDOG_MAX_SLEEP;
import static io.reticulum.constant.ResourceConstant.WINDOW_MAX;
import static io.reticulum.constant.ResourceConstant.WINDOW_MAX_FAST;
import static io.reticulum.packet.PacketContextType.RESOURCE;
import static io.reticulum.packet.PacketContextType.RESOURCE_ADV;
import static io.reticulum.packet.PacketContextType.RESOURCE_HMU;
import static io.reticulum.packet.PacketContextType.RESOURCE_ICL;
import static io.reticulum.packet.PacketContextType.RESOURCE_PRF;
import static io.reticulum.packet.PacketContextType.RESOURCE_REQ;
import static io.reticulum.packet.PacketType.PROOF;
import static io.reticulum.resource.ResourceStatus.ADVERTISED;
import static io.reticulum.resource.ResourceStatus.ASSEMBLING;
import static io.reticulum.resource.ResourceStatus.AWAITING_PROOF;
import static io.reticulum.resource.ResourceStatus.COMPLETE;
import static io.reticulum.resource.ResourceStatus.CORRUPT;
import static io.reticulum.resource.ResourceStatus.FAILED;
import static io.reticulum.resource.ResourceStatus.NONE;
import static io.reticulum.resource.ResourceStatus.QUEUED;
import static io.reticulum.resource.ResourceStatus.TRANSFERRING;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static io.reticulum.utils.IdentityUtils.fullHash;
import static io.reticulum.utils.IdentityUtils.truncatedHash;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.concurrent.Executors.defaultThreadFactory;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.compress.compressors.CompressorStreamFactory.BZIP2;
import static org.apache.commons.lang3.ArrayUtils.add;
import static org.apache.commons.lang3.ArrayUtils.insert;
import static org.apache.commons.lang3.ArrayUtils.subarray;
import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.msgpack.value.ValueFactory.newArray;
import static org.msgpack.value.ValueFactory.newBinary;
import static org.msgpack.value.ValueFactory.newInteger;

/**
 * The Resource class allows transferring arbitrary amounts
 * of data over a link. It will automatically handle sequencing,
 * compression, coordination and checksumming.
 */
@Data
@Slf4j
@EqualsAndHashCode(of = "hash")
public class Resource {

    private final Lock assambleLock = new ReentrantLock();
    private final Lock watchdogLock = new ReentrantLock();
    private final Lock receiveLock = new ReentrantLock();
    private File inputFile;
    private Link link;
    private Path storagePath;
    private volatile Instant lastActivity;
    private volatile Instant advSent;
    private volatile Instant reqSent;
    private volatile Instant reqResp;
    private volatile Instant lastPartSent;
    private volatile ResourceStatus status;
    private Consumer<Resource> callback;
    private Consumer<Resource> progressCallback;
    private List<Packet> parts = List.of();
    private List<byte[]> reqHashlist;
    private Packet advertisementPacket;

    private byte[] requestId;
    private byte[] hash;
    private byte[] truncatedHash;
    private byte[] expectedProof;
    private byte[] randomHash;
    private byte[] originalHash;
    private byte[] hashmap;
    private byte[] hashmapRaw;
    private byte[] uncompressedData;
    private byte[] compressedData;
    private byte[] data;

    private boolean compressed;
    private boolean encrypted;
    private boolean split;
    private boolean isResponse;
    private boolean initiator;
    private volatile boolean waitingForHmu;
    private volatile boolean receivingPart;
    private boolean hmuRetryOk;

    private int segmentIndex;
    private int totalSegments;
    private int flags;
    private int uncompressedSize;
    private int compressedSize;
    private int totalParts;
    private AtomicInteger outstandingParts = new AtomicInteger(0);
    private volatile int window;
    private volatile int windowMax;
    private int windowMin;
    private int windowFlexibility;
    private AtomicInteger hashmapHeight = new AtomicInteger(0);
    private int size;
    private int totalSize;
    private int grandTotalParts;
    private int consecutiveCompletedHeight = -1;
    private int maxRetries;
    private int maxAdvRetries;
    private volatile int retriesLeft;
    private int timeoutFactor;
    private int partTimeoutFactor;
    private int watchdogJobId;
    private volatile int fastRateRounds;
    private int receiverMinConsecutiveHeight;
    private int sentParts;
    private int reqSentBytes;
    private int processedParts;

    private long senderGraceTime;
    private volatile long receivedCount;
    private Long rtt;
    private volatile long rttRxdBytes;
    private long rttRxdBytesAtPartReq;
    private long reqRespRttRate;
    /**
     * milliseconds
     */
    private long timeout;
    private long reqDataRttRate;

    private double progressTotalParts;

    @SneakyThrows
    private void init(
            byte[] data,
            Link link,
            final Consumer<Resource> callback,
            Consumer<Resource> progressCallback,
            byte[] requestId,
            boolean isResponse,
            Long timeout,
            boolean autoCompress,
            byte[] originalHash,
            boolean advertise
    ) {
        this.status = NONE;
        this.link = link;
        this.timeoutFactor = link.getTrafficTimeoutFactor();
        this.progressCallback = progressCallback;
        this.requestId = requestId;
        this.isResponse = isResponse;
        this.maxRetries = MAX_RETRIES;
        this.maxAdvRetries = MAX_ADV_RETRIES;
        this.retriesLeft = this.maxRetries;
        this.partTimeoutFactor = PART_TIMEOUT_FACTOR;
        this.senderGraceTime = SENDER_GRACE_TIME;
        this.hmuRetryOk = false;
        this.watchdogJobId = 0;
        this.rttRxdBytes = 0;
        this.reqSent = null;
        this.reqRespRttRate = 0;
        this.rttRxdBytesAtPartReq = 0;
        this.fastRateRounds = 0;

        this.reqHashlist = new ArrayList<>();
        this.receiverMinConsecutiveHeight = 0;

        if (nonNull(timeout)) {
            this.timeout = timeout;
        } else {
            this.timeout = this.link.getRtt() * this.link.getTrafficTimeoutFactor();
        }

        if (nonNull(data)) {
            this.initiator = true;
            this.callback = callback;
            this.uncompressedData = data;

            var compressionBegan = Instant.now();
            if (autoCompress && uncompressedData.length < AUTO_COMPRESS_MAX_SIZE) {
                log.debug("Compressing resource data...");
                try (var baos = new ByteArrayOutputStream()) {
                    var compressor = new CompressorStreamFactory().createCompressorOutputStream(BZIP2, baos);
                    compressor.write(uncompressedData);
                    this.compressedData = baos.toByteArray();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                log.debug("Compression completed in {} milliseconds", Duration.between(compressionBegan, Instant.now()).toMillis());
            } else {
                this.compressedData = this.uncompressedData;
            }

            this.uncompressedSize = uncompressedData.length;
            this.compressedSize = compressedData.length;

            if (compressedSize < uncompressedSize && autoCompress) {
                var savedBytes = uncompressedData.length - compressedData.length;
                log.debug("Compression saved {}  bytes, sending compressed", savedBytes);

                this.data = concatArrays(
                        subarray(IdentityUtils.getRandomHash(), 0, RANDOM_HASH_SIZE),
                        this.compressedData
                );

                this.compressed = true;
                this.uncompressedData = null;
            } else {
                this.data = concatArrays(
                        subarray(IdentityUtils.getRandomHash(), 0, RANDOM_HASH_SIZE),
                        uncompressedData
                );
                this.uncompressedData = this.data;

                this.compressed = false;
                this.compressedData = null;
                if (autoCompress) {
                    log.debug("Compression did not decrease size, sending uncompressed");
                }
            }

            // Resources handle encryption directly to
            // make optimal use of packet MTU on an entire
            // encrypted stream. The Resource instance will
            // use it's underlying link directly to encrypt.
            this.data = this.link.encrypt(this.data);
            this.encrypted = true;

            this.size = this.data.length;
            this.sentParts = 0;
            var hashmapEntries = (int) Math.ceil((double) this.size / ResourceConstant.SDU);

            var hashmapOk = false;
            while (isFalse(hashmapOk)) {
                var hashmapComputationBegan = Instant.now();
                log.debug("Starting resource hashmap computation with {} entries...", hashmapEntries);

                this.randomHash = subarray(IdentityUtils.getRandomHash(), 0, RANDOM_HASH_SIZE);
                this.hash = fullHash(concatArrays(data, randomHash));
                this.truncatedHash = truncatedHash(concatArrays(data, randomHash));
                this.expectedProof = fullHash(concatArrays(data, hash));

                this.originalHash = Objects.requireNonNullElse(originalHash, this.hash);

                this.parts = new LinkedList<>();
                this.hashmap = new byte[0];
                var collisionGuardList = new LinkedList<byte[]>();
                for (int i = 0; i < hashmapEntries; i++) {
                    var d = subarray(this.data, i * SDU, (i + 1) * SDU);
                    var mapHash = getMapHash(d);

                    if (collisionGuardList.stream().anyMatch(array -> Arrays.equals(array, mapHash))) {
                        log.debug("Found hash collision in resource map, remapping...");
                        hashmapOk = false;
                        break;
                    } else {
                        hashmapOk = true;
                        collisionGuardList.add(mapHash);
                        if (collisionGuardList.size() > COLLISION_GUARD_SIZE) {
                            collisionGuardList.removeFirst();
                        }

                        var part = new Packet(link, d, RESOURCE);
                        part.pack();
                        part.setMapHash(mapHash);

                        this.hashmap = concatArrays(this.hashmap, part.getMapHash());
                        this.parts.add(part);
                    }
                }

                log.debug("Hashmap computation concluded in {} milliseconds", Duration.between(hashmapComputationBegan, Instant.now()).toMillis());
            }

            if (advertise) {
                this.advertise();
            }
        }
    }

    public Resource(byte[] data, Link link, byte[] requestId, boolean isResponse) {

    }

    public Resource(byte[] data, Link link, byte[] requestId, boolean isResponse, long timeout) {

    }

    public Resource(
            @NonNull final byte[] data,
            final Link link,
            final Consumer<Resource> callback,
            Consumer<Resource> progressCallback,
            byte[] requestId,
            boolean isResponse,
            Long timeout,
            boolean autoCompress,
            byte[] originalHash,
            boolean advertise
    ) {
        var dataSize = data.length;
        this.grandTotalParts = (int) Math.ceil((double) dataSize / SDU);
        this.totalSize = dataSize;

        this.totalSegments = 1;
        this.segmentIndex = 1;
        this.split = false;

        init(data, link, callback, progressCallback, requestId, isResponse, timeout, autoCompress, originalHash, advertise);
    }

    public Resource(@NonNull final File file, final Link link, final Consumer<Resource> callback) {
        this(file, link, callback, 1, null, null, false, null, true, null, true);
    }

    public Resource(@NonNull File inputFile, Link link, Consumer<Resource> callback, int segmentIndex, byte[] originalHash, Consumer<Resource> progressCallback) {
        this(inputFile, link, callback, segmentIndex, progressCallback, null, false, null, true, originalHash, true);
    }

    public Resource(
            @NonNull final File file,
            final Link link,
            final Consumer<Resource> callback,
            int segmentIndex,
            Consumer<Resource> progressCallback,
            byte[] requestId,
            boolean isResponse,
            Long timeout,
            boolean autoCompress,
            byte[] originalHash,
            boolean advertise
    ) {
        var resourceData = new byte[0];
        if (file.isFile()) {
            try (var fileInputStream = new FileInputStream(file)) {
                var dataSize = fileInputStream.available();

                this.totalSize = dataSize;
                this.grandTotalParts = (int) Math.ceil((double) dataSize / ResourceConstant.SDU);

                if (dataSize <= MAX_EFFICIENT_SIZE) {
                    this.totalSegments = 1;
                    this.segmentIndex = 1;
                    this.split = false;

                    resourceData = fileInputStream.readAllBytes();
                } else {
                    this.totalSegments = ((dataSize - 1) * MAX_EFFICIENT_SIZE) + 1;
                    this.segmentIndex = segmentIndex;
                    this.split = true;
                    var seekIndex = segmentIndex - 1;
                    var seekPosition = seekIndex * MAX_EFFICIENT_SIZE;

                    fileInputStream.skip(seekPosition);
                    resourceData = fileInputStream.readNBytes(MAX_EFFICIENT_SIZE);
                    this.inputFile = file;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            init(resourceData, link, callback, progressCallback, requestId, isResponse, timeout, autoCompress, originalHash, advertise);
        }
    }

    public Resource(Link link, byte[] requestId) {
        this.link = link;
        this.requestId = requestId;
    }

    public static Resource accept(Packet packet, Consumer<Resource> callback) {
        return null;
    }

    public static Resource accept(
            Packet packet,
            Consumer<Resource> callback,
            Consumer<Resource> progressCallback,
            byte[] requestId
    ) {
        return null;
    }

    public void hashmapUpdatePacket(byte[] plaintext) {
        if (isFalse(status == FAILED)) {
            this.lastActivity = Instant.now();
            this.retriesLeft = this.maxRetries;

            var packed = subarray(plaintext, HASHLENGTH / 8, plaintext.length);
            try (var packer = MessagePack.newDefaultUnpacker(packed)) {
                var update = packer.unpackValue().asArrayValue();

                hashmapUpdate(update.get(0).asIntegerValue().asInt(), update.get(1).asBinaryValue().asByteArray());
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public synchronized void hashmapUpdate(final int segment, @NonNull final byte[] hashmap) {
        if (isFalse(status == FAILED)) {
            status = TRANSFERRING;
            var hashes = hashmap.length / MAPHASH_LEN;
            for (int i = 0; i < hashes; i++) {
                var index = i * segment * HASHMAP_MAX_LEN;
                if (this.hashmap.length - 1 < index) {
                    this.hashmapHeight.getAndIncrement();
                }
                this.hashmap = insert(index, this.hashmap, subarray(hashmap, i * MAPHASH_LEN, (i + 1) * MAPHASH_LEN));
            }

            this.waitingForHmu = false;
            requestNext();
        }
    }

    private byte[] getMapHash(final byte[] data) {
        return subarray(fullHash(concatArrays(data, this.randomHash)), 0, MAPHASH_LEN);
    }

    /**
     * Advertise the resource. If the other end of the link accepts
     * the resource advertisement it will begin transferring.
     */
    private void advertise() {
        defaultThreadFactory().newThread(this::advertiseJob).start();
    }

    @SneakyThrows
    private synchronized void watchdogJob() {
        this.watchdogJobId++;
        var thisJobId = this.watchdogJobId;

        while(status.getValue() < ASSEMBLING.getValue() && thisJobId == watchdogJobId) {
            if (watchdogLock.tryLock()) {
                var sleepTime = 0L;

                if (status == ADVERTISED) {
                    sleepTime = Duration.between(Instant.now(), advSent.minusMillis(timeout)).toMillis();
                    if (sleepTime < 0) {
                        if (retriesLeft <= 0) {
                            log.debug("Resource transfer timeout after sending advertisement");
                            cancel();
                            sleepTime = 1;
                        } else {
                            try {
                                log.debug("No part requests received, retrying resource advertisement...");
                                retriesLeft--;
                                advertisementPacket = new Packet(link, new ResourceAdvertisement(this).pack(), RESOURCE_ADV);
                                advertisementPacket.send();
                                lastActivity = Instant.now();
                                advSent = lastActivity;
                                sleepTime = 1;
                            } catch (Exception e) {
                                log.error("Could not resend advertisement packet, cancelling resource");
                                cancel();
                            }
                        }
                    }
                } else if (status == TRANSFERRING) {
                    if (isFalse(initiator)) {
                        var rtt = Objects.requireNonNullElseGet(this.rtt, () -> this.link.getRtt());
                        var windowRemaining = this.outstandingParts.get();

                        var retriesUsed = this.maxRetries - this.retriesLeft;
                        var extraWait = retriesUsed * PER_RETRY_DELAY;
                        sleepTime = Duration.between(
                                Instant.now(),
                                this.lastActivity
                                        .plusMillis(rtt * (this.partTimeoutFactor + windowRemaining))
                                        .plusMillis((long) (RETRY_GRACE_TIME + extraWait))
                        ).toMillis();

                        if (sleepTime < 0) {
                            if (retriesLeft > 0) {
                                log.debug("Timed out waiting for {} part{}, requesting retry", outstandingParts.get(), outstandingParts.get() == 1 ? "" : "s");
                                if (this.window > this.windowMin) {
                                    this.window--;
                                    if (this.windowMax > this.windowMin) {
                                        this.windowMax--;
                                        if ((this.windowMax - this.window) > (this.windowFlexibility - 1)) {
                                            this.windowMax--;
                                        }
                                    }
                                }

                                sleepTime = 1;
                                this.retriesLeft--;
                                this.waitingForHmu = false;
                                requestNext();
                            } else {
                                cancel();
                                sleepTime = 1;
                            }
                        }
                    } else {
                        var maxExtraWait = 0L;
                        for (int r = 0; r < MAX_RETRIES; r++) {
                            maxExtraWait += (r + 1) * PER_RETRY_DELAY;
                        }
                        var maxWait = this.rtt * this.timeoutFactor * this.maxRetries + this.senderGraceTime + maxExtraWait;
                        sleepTime = Duration.between(Instant.now(), this.lastActivity.plusMillis(maxWait)).toMillis();
                        if (sleepTime < 0) {
                            log.debug("Resource timed out waiting for part requests");
                            cancel();
                            sleepTime = 1;
                        }
                    }
                } else if (status == AWAITING_PROOF) {
                    sleepTime = Duration.between(
                            Instant.now(),
                            this.lastPartSent.plusMillis(this.rtt * this.timeoutFactor + this.senderGraceTime)
                    ).toMillis();
                    if (sleepTime < 0) {
                        if (this.retriesLeft <= 0) {
                            log.debug("Resource timed out waiting for proof");
                            cancel();
                            sleepTime = 1;
                        } else {
                            log.debug("All parts sent, but no resource proof received, querying network cache...");
                            this.retriesLeft--;
                            var expectedData = concatArrays(this.hash, this.expectedProof);
                            var expectedProofPacket = new Packet(link, expectedData, PROOF, RESOURCE_PRF);
                            expectedProofPacket.pack();
                            Transport.getInstance().cacheRequest(expectedProofPacket.getHash(), link);
                            this.lastPartSent = Instant.now();
                            sleepTime = 1;
                        }
                    }
                }

                if (sleepTime == 0) {
                    log.warn("Warning! Link watchdog sleep time of 0!");
                }
                if (sleepTime < 0) {
                    log.error("Timing error, cancelling resource transfer.");
                    cancel();
                }
                if (sleepTime > 0) {
                    Thread.sleep(Math.min(sleepTime, WATCHDOG_MAX_SLEEP));
                }
            }
        }
    }

    @SneakyThrows
    private synchronized void advertiseJob() {
        this.advertisementPacket = new Packet(link, new ResourceAdvertisement(this).pack(), RESOURCE_ADV);
        while (isFalse(link.readyForNewResource())) {
            this.status = QUEUED;
            Thread.sleep(250);
        }

        try {
            advertisementPacket.send();
            lastActivity = Instant.now();
            advSent = lastActivity;
            rtt = 0L;
            status = ADVERTISED;
            retriesLeft = maxAdvRetries;
            link.registerOutgoingResource(this);

            log.debug("Sent resource advertisement for {}", this);
        } catch (Exception e) {
            log.error("Could not advertise resource.", e);
            cancel();

            return;
        }

        watchdogJobStart();
    }

    @SneakyThrows
    private void assemble() {
        if (isFalse(status == FAILED)) {
            try {
                status = ASSEMBLING;
                var stream = parts.stream()
                        .map(Packet::getCiphertext)
                        .reduce(IdentityUtils::concatArrays)
                        .orElse(new byte[0]);
                var data = this.encrypted ? this.link.decrypt(stream) : stream;

                //Strip off random hash
                data = subarray(data, RANDOM_HASH_SIZE, data.length);

                if (this.compressed) {
                    try (var baos = new ByteArrayInputStream(data)) {
                        var decompressor = new CompressorStreamFactory().createCompressorInputStream(BZIP2, baos);
                        this.data = decompressor.readAllBytes();
                    }
                } else {
                    this.data = data;
                }

                var calculatedHash = IdentityUtils.fullHash(concatArrays(this.data, this.randomHash));

                if (Arrays.equals(calculatedHash, this.hash)) {
                    Files.write(storagePath, this.data, APPEND, WRITE, CREATE);
                    status = COMPLETE;
                    prove();
                } else {
                    status = CORRUPT;
                }
            } catch (Exception e) {
                log.error("Error while assembling received resource.", e);
                this.status = CORRUPT;
            }

            if (this.segmentIndex == this.totalSegments) {
                if (nonNull(this.callback)) {
                    this.data = Files.readAllBytes(storagePath);
                    try {
                        this.callback.accept(this);
                    } catch (Exception e) {
                        log.error("Error while executing resource assembled callback from {}", this, e);
                    }
                }
            } else {
                log.debug("Resource segment {}  of {} received, waiting for next segment to be announced", this.segmentIndex, this.totalSegments);
            }
        }

        this.assambleLock.unlock();
    }

    private void prove() {
        if (status == FAILED) {
            try {
                var proof = IdentityUtils.fullHash(concatArrays(this.data, this.hash));
                var proofData = concatArrays(this.hash, proof);
                var proofPacket = new Packet(link, proofData, PROOF, RESOURCE_PRF);
                proofPacket.send();
            } catch (Exception e) {
                log.error("Could not send proof packet, cancelling resource", e);
                cancel();
            }
        }
    }

    public synchronized void validateProof(final byte[] proofData) {
        if (isFalse(status == FAILED)) {
            if (ArrayUtils.getLength(proofData) == HASHLENGTH / 8 * 2) {
                if (Arrays.equals(subarray(proofData, HASHLENGTH / 8, proofData.length), this.expectedProof)) {
                    status = COMPLETE;
                    this.link.resourceConcluded(this);
                    if (this.segmentIndex == this.totalSegments) {
                        // If all segments were processed, we'll
                        // signal that the resource sending concluded
                        if (nonNull(this.callback)) {
                            try {
                                this.callback.accept(this);
                            } catch (Exception e) {
                                log.error("Error while executing resource concluded callback from {}", this);
                            }
                        }
                    } else {
                        // Otherwise we'll recursively create the
                        // next segment of the resource
                        new Resource(inputFile, link, callback, segmentIndex + 1, originalHash, progressCallback);
                    }
                }
            }
        }
    }

    public synchronized void receivePart(@NonNull final Packet packet) {
        if (receiveLock.tryLock()) {
            try {
                this.receivingPart = true;
                this.lastActivity = Instant.now();
                this.retriesLeft = this.maxRetries;

                var rtt = 0L;
                if (isNull(this.reqResp)) {
                    this.reqResp = this.lastActivity;
                    rtt = Duration.between(this.reqSent, this.reqResp).toMillis();

                    this.partTimeoutFactor = PART_TIMEOUT_FACTOR_AFTER_RTT;
                    if (isNull(this.rtt)) {
                        this.rtt = this.link.getRtt();
                        watchdogJobStart();
                    } else if (rtt < this.rtt) {
                        this.rtt = (long) Math.max(this.rtt - this.rtt * 0.05, rtt);
                    } else if (rtt > this.rtt) {
                        this.rtt = (long) Math.min(this.rtt - this.rtt * 0.05, rtt);
                    }

                    if (rtt > 0) {
                        var reqRespCost = ArrayUtils.getLength(packet.getRaw()) + this.reqSentBytes;
                        this.reqRespRttRate = reqRespCost / rtt;

                        if (this.reqRespRttRate > RATE_FAST && this.fastRateRounds < FAST_RATE_THRESHOLD) {
                            this.fastRateRounds++;

                            if (this.fastRateRounds == FAST_RATE_THRESHOLD) {
                                this.windowMax = WINDOW_MAX_FAST;
                            }
                        }
                    }
                } else if (isFalse(this.status == FAILED)) {
                    this.status = TRANSFERRING;
                    var partData = packet.getData();
                    var partHash = getMapHash(partData);

                    var i = Math.max(this.consecutiveCompletedHeight, 0);
                    while (this.hashmap.length < i + this.window) {
                        if (Arrays.equals(partHash, subarray(this.hashmap, i, i + this.window))) {
                            if (CollectionUtils.size(this.parts) <= i) {
                                // Insert data into parts list
                                this.parts.set(i, packet);
                                this.rttRxdBytes += partData.length;
                                this.receivedCount++;
                                this.outstandingParts.getAndDecrement();

                                // Update consecutive completed pointer
                                if (i == this.consecutiveCompletedHeight + 1) {
                                    this.consecutiveCompletedHeight = i;
                                }

                                var cp = this.consecutiveCompletedHeight + 1;
                                while (cp < CollectionUtils.size(this.parts)) {
                                    this.consecutiveCompletedHeight = cp;
                                    cp++;
                                }

                                if (nonNull(this.progressCallback)) {
                                    try {
                                        this.progressCallback.accept(this);
                                    } catch (Exception e) {
                                        log.error("Error while executing progress callback from {}.", this, e);
                                    }
                                }
                            }
                        }
                        i++;
                    }

                    this.receivingPart = false;

                    if (this.receivedCount == this.totalParts && assambleLock.tryLock()) {
                        assemble();
                    } else if (this.outstandingParts.get() == 0) {
                        // TODO: 07.05.2023 Figure out if there is a mathematically
                        // optimal way to adjust windows
                        if (this.window < this.windowMax) {
                            this.window++;
                            if ((this.window - this.windowMin) > (this.windowFlexibility - 1)) {
                                this.windowMin++;
                            }
                        }

                        if (nonNull(this.reqSent)) {
                            rtt = Duration.between(this.reqSent, Instant.now()).toMillis();
                            var reqTransferred = this.rttRxdBytes - this.rttRxdBytesAtPartReq;

                            if (rtt != 0) {
                                this.reqDataRttRate = reqTransferred / rtt;
                                this.rttRxdBytesAtPartReq = this.rttRxdBytes;

                                if (this.reqDataRttRate > RATE_FAST && this.fastRateRounds < FAST_RATE_THRESHOLD) {
                                    this.fastRateRounds++;

                                    if (this.fastRateRounds == FAST_RATE_THRESHOLD) {
                                        this.windowMax = WINDOW_MAX_FAST;
                                    }
                                }
                            }
                        }

                        requestNext();
                    }
                } else {
                    this.receivingPart = false;
                }
            } finally {
                receiveLock.unlock();
            }
        }
    }

    /**
     * Called on incoming resource to send a request for more data
     */
    @SneakyThrows
    private void requestNext() {
        while (this.receivingPart) {
            //sleep
        }

        if (isFalse(status == FAILED)) {
            if (isFalse(this.waitingForHmu)) {
                this.outstandingParts.set(0);
                var hashmapExhausted = HASHMAP_IS_NOT_EXHAUSTED;
                var requestedHashes = new byte[0];

                var i = 0; var pn = consecutiveCompletedHeight + 1;
                final var searchStart = pn;
                final var searchSize = this.window;

                for (Packet part : parts.subList(searchStart, searchSize)) {
                    if (isNull(part)) {
                        if (this.hashmap.length - 1 > pn) {
                            var partHash = this.hashmap[pn];
                            requestedHashes = add(requestedHashes, partHash);
                            this.outstandingParts.getAndIncrement();
                            i++;
                        } else {
                            hashmapExhausted = HASHMAP_IS_EXHAUSTED;
                        }
                    }

                    pn++;
                    if (i >= this.window || hashmapExhausted == HASHMAP_IS_EXHAUSTED) {
                        break;
                    }
                }

                var hmuPart = new byte[hashmapExhausted];
                if (hashmapExhausted == HASHMAP_IS_EXHAUSTED) {
                    var lastMapHash = this.hashmap[this.hashmapHeight.get() - 1];
                    hmuPart = add(hmuPart, lastMapHash);
                    this.waitingForHmu = false;
                }

                var requestData = concatArrays(hmuPart, this.hash, requestedHashes);
                var requestPacket = new Packet(link, requestData, RESOURCE_REQ);

                try {
                    requestPacket.send();
                    this.lastActivity = Instant.now();
                    this.reqSent = lastActivity;
                    this.reqResp = null;
                } catch (Exception e) {
                    log.error("Could not send resource request packet, cancelling resource");
                    cancel();
                }
            }
        }
    }

    public synchronized void request(byte[] requestData) {
        if (isFalse(status == FAILED)) {
            var rtt = Duration.between(this.advSent, Instant.now()).toMillis();
            if (isNull(this.rtt)) {
                this.rtt = rtt;
            }

            if (status != TRANSFERRING) {
                status = TRANSFERRING;
                watchdogJobStart();
            }

            this.retriesLeft = this.maxRetries;

            var wantsMoreHashmap = requestData[0] == HASHMAP_IS_EXHAUSTED;
            var pad = wantsMoreHashmap ? 1 + MAPHASH_LEN : 1;

            var requestedHashes = subarray(requestData, pad + HASHLENGTH / 8, requestData.length);


            // Define the search scope
            var searchStart = this.receiverMinConsecutiveHeight;
            var searchEnd = this.receiverMinConsecutiveHeight + COLLISION_GUARD_SIZE;

            var mapHashes = new ArrayList<byte[]>();
            for (int i = 0; i < requestedHashes.length / MAPHASH_LEN; i++) {
                var mapHash = subarray(requestedHashes, i * MAPHASH_LEN, (i + 1) * MAPHASH_LEN);
                mapHashes.add(mapHash);
            }

            var searchScope = this.parts.subList(searchStart, searchEnd);
            var requestedParts = searchScope.stream()
                    .filter(part -> mapHashes.stream().anyMatch(hash -> Arrays.equals(hash, part.getMapHash())))
                    .collect(toList());

            for (Packet part : requestedParts) {
                try {
                    if (isFalse(part.isSent())) {
                        part.send();
                        this.sentParts++;
                    } else {
                        part.resend();
                    }

                    this.lastActivity = Instant.now();
                    this.lastPartSent = this.lastActivity;
                } catch (Exception e) {
                    log.error("Resource could not send parts, cancelling transfer!");
                    cancel();
                }
            }

            if (wantsMoreHashmap) {
                var lastHashMap = subarray(requestData, 1, MAPHASH_LEN + 1);

                var partIndex = this.receiverMinConsecutiveHeight;
                searchStart = partIndex;
                searchEnd = this.receiverMinConsecutiveHeight + COLLISION_GUARD_SIZE;
                for (Packet part : this.parts.subList(searchStart, searchEnd)) {
                    partIndex++;
                    if (Arrays.equals(part.getMapHash(), lastHashMap)) {
                        break;
                    }
                }

                this.receiverMinConsecutiveHeight = Math.max(partIndex - 1 - WINDOW_MAX, 0);

                var segment = 0;
                if (partIndex % HASHMAP_MAX_LEN != 0) {
                    log.error("Resource sequencing error, cancelling transfer!");
                    cancel();
                    return;
                } else {
                    segment = partIndex / HASHMAP_MAX_LEN;
                }

                var hashMapStart = segment * HASHMAP_MAX_LEN;
                var hashMapEnd = Math.min((segment + 1) * HASHMAP_MAX_LEN, this.parts.size());

                var hashMap = new byte[0];
                for (int i = hashMapStart; i < hashMapEnd; i++) {
                    hashMap = concatArrays(hashMap, subarray(this.hashmap, i * MAPHASH_LEN, (i + 1) * MAPHASH_LEN));
                }

                try (var packer = MessagePack.newDefaultBufferPacker()) {
                    packer.packValue(newArray(newInteger(segment), newBinary(hashMap)));
                    var hmu = concatArrays(this.hash, packer.toByteArray());
                    var hmuPacket = new Packet(link, hmu, RESOURCE_HMU);
                    hmuPacket.send();
                    this.lastActivity = Instant.now();
                } catch (Exception ex) {
                    log.error("Could not send resource HMU packet, cancelling resource", ex);
                    cancel();
                }
            }

            if (this.sentParts == this.parts.size()) {
                this.status = AWAITING_PROOF;
            }

            if (nonNull(this.progressCallback)) {
                try {
                    this.progressCallback.accept(this);
                } catch (Exception e) {
                    log.error("Error while executing progress callback from {}", this, e);
                }
            }
        }
    }

    /**
     * Cancels transferring the resource.
     */
    public synchronized void cancel() {
        if (nonNull(status) && status.getValue() < COMPLETE.getValue()) {
            status = FAILED;
            if (initiator) {
                if (link.getStatus() == LinkStatus.ACTIVE) {
                    try {
                        var cancelPacket = new Packet(link, hash, RESOURCE_ICL);
                        cancelPacket.send();
                    } catch (Exception e) {
                        log.error("Could not send resource cancel packet.", e);
                    }
                }
                link.cancelOutgoingResource(this);
            } else {
                link.cancelOutgoingResource(this);
            }

            if (nonNull(callback)) {
                try {
                    link.resourceConcluded(this);
                    callback.accept(this);
                } catch (Exception e) {
                    log.error("Error while executing callbacks on resource cancel from {}. ", this, e);
                }
            }
        }
    }

    /**
     * @return The current progress of the resource transfer as a *float* between 0.0 and 1.0.
     */
    public double getProgress() {
        if (initiator) {
            this.processedParts = (int) ((this.segmentIndex - 1) * Math.ceil((double) MAX_EFFICIENT_SIZE / SDU));
            this.processedParts += this.sentParts;
            this.progressTotalParts = this.grandTotalParts;
        } else {
            this.processedParts = (int) ((segmentIndex - 1) * Math.ceil((double) MAX_EFFICIENT_SIZE / SDU));
            this.processedParts += this.receivedCount;
            if (this.split) {
                this.progressTotalParts = Math.ceil((double) this.totalSize / SDU);
            } else {
                this.progressTotalParts = this.totalParts;
            }
        }

        return Math.min(1.0, this.processedParts / this.progressTotalParts);
    }

    private int transferSize() {
        return this.size;
    }

    public int getDataSize() {
        return this.totalSize;
    }

    public int getParts() {
        return this.totalParts;
    }

    public int getSegments() {
        return this.totalSegments;
    }

    public String toString() {
        return String.format("<%s/%s>", Hex.encodeHexString(this.hash), Hex.encodeHexString(this.link.getLinkId()));
    }

    public void watchdogJobStart() {
        defaultThreadFactory().newThread(this::watchdogJob).start();
    }
}
