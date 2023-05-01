package io.reticulum.resource;

import io.reticulum.constant.IdentityConstant;
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
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.msgpack.core.MessagePack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
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

import static io.reticulum.constant.ResourceConstant.AUTO_COMPRESS_MAX_SIZE;
import static io.reticulum.constant.ResourceConstant.COLLISION_GUARD_SIZE;
import static io.reticulum.constant.ResourceConstant.HASHMAP_IS_EXHAUSTED;
import static io.reticulum.constant.ResourceConstant.HASHMAP_IS_NOT_EXHAUSTED;
import static io.reticulum.constant.ResourceConstant.HASHMAP_MAX_LEN;
import static io.reticulum.constant.ResourceConstant.MAPHASH_LEN;
import static io.reticulum.constant.ResourceConstant.MAX_ADV_RETRIES;
import static io.reticulum.constant.ResourceConstant.MAX_EFFICIENT_SIZE;
import static io.reticulum.constant.ResourceConstant.MAX_RETRIES;
import static io.reticulum.constant.ResourceConstant.PART_TIMEOUT_FACTOR;
import static io.reticulum.constant.ResourceConstant.RANDOM_HASH_SIZE;
import static io.reticulum.constant.ResourceConstant.SDU;
import static io.reticulum.constant.ResourceConstant.SENDER_GRACE_TIME;
import static io.reticulum.packet.PacketContextType.RESOURCE;
import static io.reticulum.packet.PacketContextType.RESOURCE_ADV;
import static io.reticulum.packet.PacketContextType.RESOURCE_ICL;
import static io.reticulum.packet.PacketContextType.RESOURCE_REQ;
import static io.reticulum.resource.ResourceStatus.ADVERTISED;
import static io.reticulum.resource.ResourceStatus.COMPLETE;
import static io.reticulum.resource.ResourceStatus.FAILED;
import static io.reticulum.resource.ResourceStatus.NONE;
import static io.reticulum.resource.ResourceStatus.QUEUED;
import static io.reticulum.resource.ResourceStatus.TRANSFERRING;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static io.reticulum.utils.IdentityUtils.fullHash;
import static io.reticulum.utils.IdentityUtils.truncatedHash;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.concurrent.Executors.defaultThreadFactory;
import static org.apache.commons.compress.compressors.CompressorStreamFactory.BZIP2;
import static org.apache.commons.lang3.ArrayUtils.add;
import static org.apache.commons.lang3.ArrayUtils.insert;
import static org.apache.commons.lang3.ArrayUtils.subarray;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

/**
 * The Resource class allows transferring arbitrary amounts
 * of data over a link. It will automatically handle sequencing,
 * compression, coordination and checksumming.
 */
@Data
@Slf4j
@EqualsAndHashCode(of = "hash")
public class Resource {

    private final Lock watchdogLock = new ReentrantLock();
    private File inputFile;
    private Link link;
    private Path storagePath;
    private volatile Instant lastActivity;
    private volatile Instant advSent;
    private volatile Instant reqSent;
    private volatile Instant reqResp;
    private ResourceStatus status;
    private Consumer<Resource> callback;
    private Consumer<Resource> progressCallback;
    private List<Packet> parts;
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
    private boolean receivingPart;
    private boolean hmuRetryOk;

    private int segmentIndex;
    private int totalSegments;
    private int flags;
    private int uncompressedSize;
    private int compressedSize;
    private int totalParts;
    private AtomicInteger outstandingParts = new AtomicInteger(0);
    private int window;
    private int windowMax;
    private int windowMin;
    private int windowFlexibility;
    private AtomicInteger hashmapHeight = new AtomicInteger(0);
    private int size;
    private int totalSize;
    private int grandTotalParts;
    private int consecutiveCompletedHeight;
    private int maxRetries;
    private int maxAdvRetries;
    private int retriesLeft;
    private int timeoutFactor;
    private int partTimeoutFactor;
    private int senderGraceTime;
    private int watchdogJobId;
    private int reqRespRttRate;
    private int fastRateRounds;
    private int receiverMinConsecutiveHeight;
    private int sentParts;

    private long receivedCount;
    private long rtt;
    private long rttRxdBytes;
    private long rttRxdBytesAtPartReq;
    /**
     * milliseconds
     */
    private long timeout;

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
                } catch (IOException | CompressorException e) {
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

    private byte[] getMapHash(final byte[] data) throws IOException {
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
            rtt = 0;
            status = ADVERTISED;
            retriesLeft = maxAdvRetries;
            link.registerOutgoingResource(this);

            log.debug("Sent resource advertisement for {}", this);
        } catch (Exception e) {
            log.error("Could not advertise resource.", e);
            cancel();
            return;
        }

        watchdogJobStarter();
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

    public void hashmapUpdatePacket(byte[] plaintext) {
        if (isFalse(status == FAILED)) {
            this.lastActivity = Instant.now();
            this.retriesLeft = this.maxRetries;

            var packed = subarray(plaintext, IdentityConstant.HASHLENGTH / 8, plaintext.length);
            try (var packer = MessagePack.newDefaultUnpacker(packed)) {
                var update = packer.unpackValue().asArrayValue();

                hashmapUpdate(update.get(0).asIntegerValue().asInt(), update.get(1).asBinaryValue().asByteArray());
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public void receivePart(Packet packet) {

    }

    public void validateProof(byte[] data) {

    }

    public double getProgress() {
        return 0;
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

                var offset = consecutiveCompletedHeight > 0 ? 1 : 0;
                var i = 0;
                var pn = consecutiveCompletedHeight + offset;
                final var searchStart = pn;

                for (Packet part : parts.subList(searchStart, searchStart + this.window)) {
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

    public void watchdogJobStarter() {
        defaultThreadFactory().newThread(this::watchdogJob).start();
    }

    private void watchdogJob() {

    }

    public void request(byte[] plaintext) {

    }
}
