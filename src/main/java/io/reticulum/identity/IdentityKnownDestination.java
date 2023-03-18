package io.reticulum.identity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reticulum.packet.Packet;
import io.reticulum.Transport;
import io.reticulum.constant.IdentityConstant;
import io.reticulum.destination.Destination;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ArrayUtils;
import org.msgpack.jackson.dataformat.MessagePackMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static io.reticulum.constant.IdentityConstant.KEYSIZE;
import static io.reticulum.constant.IdentityConstant.NAME_HASH_LENGTH;
import static io.reticulum.constant.IdentityConstant.SIGLENGTH;
import static io.reticulum.constant.PacketConstant.ANNOUNCE;
import static io.reticulum.constant.ReticulumConstant.TRUNCATED_HASHLENGTH;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static io.reticulum.utils.IdentityUtils.fullHash;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNullElse;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

/**
 * This class is used to manage identities in Reticulum. It provides methods
 * for encryption, decryption, signatures and verification, and is the basis
 * for all encrypted communication over Reticulum networks.
 */
@NoArgsConstructor(access = PRIVATE)
@Slf4j
public class IdentityKnownDestination {
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();
    private static final ObjectMapper OBJECT_MAPPER = new MessagePackMapper();

    static final String KNOWN_DESTINATIONS_FILE_NAME = "known_destinations";

    static final Map<String, DestinationData> KNOWN_DESTINATIONS = new ConcurrentHashMap<>();

    // TODO: Improve the storage method so we don't have to
    // deserialize and serialize the entire table on every
    // save, but the only changes. It might be possible to
    // simply overwrite on exit now that every local client
    // disconnect triggers a data persist.
    public static void saveKnownDestinations() {
        WRITE_LOCK.lock();
        var start = System.currentTimeMillis();
        var destinationFilePath = Transport.getInstance().getOwner().getStoragePath().resolve(KNOWN_DESTINATIONS_FILE_NAME);
        try {
            if (Files.isReadable(destinationFilePath)) {
                var storage = OBJECT_MAPPER.readValue(
                        destinationFilePath.toFile(),
                        new TypeReference<Map<String, DestinationData>>() {}
                );
                storage.forEach(KNOWN_DESTINATIONS::putIfAbsent);
                Files.delete(destinationFilePath);
            }
            log.debug("Saving {} known destinations to storage...", KNOWN_DESTINATIONS.keySet().size());
            OBJECT_MAPPER.writeValue(destinationFilePath.toFile(), KNOWN_DESTINATIONS);
            log.debug("Saved known destinations to storage in {} ms.", System.currentTimeMillis() - start);
        } catch (IOException e) {
            log.error("Error while saving known destinations to disk", e);
        }
        WRITE_LOCK.unlock();
    }

    public static void loadKnownDestinations() {
        try {
            var destinationFilePath = Transport.getInstance().getOwner().getStoragePath().resolve(KNOWN_DESTINATIONS_FILE_NAME);
            if (Files.isReadable(destinationFilePath)) {
                var storage = OBJECT_MAPPER.readValue(
                        destinationFilePath.toFile(),
                        new TypeReference<Map<String, DestinationData>>() {}
                );
                storage.forEach((knownDestination, value) -> {
                    if (length(knownDestination) == TRUNCATED_HASHLENGTH / 8) {
                        KNOWN_DESTINATIONS.put(knownDestination, storage.get(knownDestination));
                    }
                });
                log.info("Loaded {} known destination from storage", storage.keySet().size());
            } else {
                log.info("Destinations file does not exist, no known destinations loaded");
            }
        } catch (IOException e) {
            log.error("Error loading known destinations from disk, file will be recreated on exit", e);
        }
    }

    public static boolean validateAnnounce(final Packet packet) {
        try {
            if (packet.getPacketType() == ANNOUNCE) {
                var destinationHash = packet.getDestinationHash();
                var destinationHashString = Hex.encodeHexString(destinationHash);
                var publicKey = Arrays.copyOfRange(packet.getData(), 0, KEYSIZE / 8);
                var nameHash = Arrays.copyOfRange(packet.getData(), KEYSIZE / 8, KEYSIZE / 8 + IdentityConstant.NAME_HASH_LENGTH / 8);
                var randomHash = Arrays.copyOfRange(packet.getData(), KEYSIZE / 8 + NAME_HASH_LENGTH / 8, KEYSIZE / 8 + NAME_HASH_LENGTH / 8 + 10);
                var signature = Arrays.copyOfRange(packet.getData(), KEYSIZE / 8 + NAME_HASH_LENGTH / 8 + 10, KEYSIZE / 8 + NAME_HASH_LENGTH / 8 + 10 + SIGLENGTH / 8);

                var appData = new byte[0];
                if (packet.getData().length > KEYSIZE / 8 + NAME_HASH_LENGTH / 8 + 10 + SIGLENGTH / 8) {
                    appData = Arrays.copyOfRange(packet.getData(), KEYSIZE / 8 + NAME_HASH_LENGTH / 8 + 10 + SIGLENGTH / 8, packet.getData().length);
                } else {
                    appData = null;
                }

                byte[] signedData = concatArrays(destinationHash, publicKey, nameHash, requireNonNullElse(appData, new byte[0]));

                var announcedIdentity = new Identity(false);
                announcedIdentity.loadPublicKey(publicKey);

                if (nonNull(announcedIdentity.getSigPub()) && announcedIdentity.validate(signature, signedData)) {
                    var hashHaterial = concatArrays(nameHash, announcedIdentity.getHash());
                    var expectedHash = Arrays.copyOfRange(fullHash(hashHaterial), 0, TRUNCATED_HASHLENGTH / 8);
                    if (Arrays.equals(destinationHash, expectedHash)) {
                        // Check if we already have a public key for this destination
                        // and make sure the public key is not different.
                        if (KNOWN_DESTINATIONS.containsKey(destinationHashString)
                                && isFalse(Arrays.equals(publicKey, KNOWN_DESTINATIONS.get(destinationHashString).getPublicKey()))) {
                            // In reality, this should never occur, but in the odd case
                            // that someone manages a hash collision, we reject the announce.
                            log.error(
                                    "Received announce with valid signature and destination hash, but announced public key does not match already known public key.\n" +
                                            "This may indicate an attempt to modify network paths, or a random hash collision. The announce was rejected."
                            );

                            return false;
                        }
                        remember(packet.getHash(), destinationHash, publicKey, appData);

                        var signalStr = "";
                        if (nonNull(packet.getRssi()) || nonNull(packet.getSnr())) {
                            var stringBuilder = new StringBuilder(" [");
                            if (nonNull(packet.getRssi())) {
                                stringBuilder.append("RSSI ").append(packet.getRssi()).append("dBm");
                                if (nonNull(packet.getSnr())) {
                                    stringBuilder.append(", ");
                                }
                            }
                            if (nonNull(packet.getSnr())) {
                                stringBuilder.append("SNR ").append(packet.getSnr()).append("dB");
                            }
                            stringBuilder.append("]");

                            signalStr = stringBuilder.toString();
                        }

                        if (nonNull(packet.getTransportId())){
                            log.trace("Valid announce for {} {} hops away, received via {} on {} {}",
                                    destinationHashString, packet.getHops(), Hex.encodeHexString(packet.getTransportId()),
                                    packet.getReceivingInterface(), signalStr);
                        } else {
                            log.trace("Valid announce for {} {} hops away, received on {} {}",
                                    destinationHashString, packet.getHops(), packet.getReceivingInterface(), signalStr);
                        }

                        return true;
                    } else {
                        log.debug("Received invalid announce for {}: Destination mismatch.", destinationHashString);

                        return false;
                    }
                } else {
                    log.debug("Received invalid announce for {}: Invalid signature.", destinationHashString);

                    return false;
                }
            }
        } catch (IOException e) {
            log.error("Error occurred while validating announce.", e);
        }

        return false;
    }

    public static void remember(@NonNull byte[] packetHash, @NonNull byte[] destinationHash, @NonNull byte[] publicKey, byte[] app_data) {
        var key = Hex.encodeHexString(destinationHash);
        if (publicKey.length != KEYSIZE / 8) {
            throw new  IllegalArgumentException(
                    String.format("Can't remember %s, the public key size of %s is not valid.", key, publicKey.length)
            );
        }

        KNOWN_DESTINATIONS.put(key, new DestinationData(System.currentTimeMillis(), packetHash, publicKey, app_data));
    }

    /**
     * Recall identity for a destination hash.
     *
     * @param destinationHash estination hash as <strong>byte[]</strong>.
     * @return An {@link Identity} instance that can be used to create an outgoing {@link Destination},
     * or null if the destination is unknown
     */
    public static Identity recall(@NonNull byte[] destinationHash) {
        var key = Hex.encodeHexString(destinationHash);
        if (KNOWN_DESTINATIONS.containsKey(key)) {
            var identityData = KNOWN_DESTINATIONS.get(key);
            var identity = new Identity(false);
            identity.loadPublicKey(identityData.getPublicKey());
            identity.setAppData(identityData.getAppData());

            return identity;
        } else {
            return Transport.getInstance().getDestinations().stream()
                    .filter(destination -> Arrays.equals(destinationHash, destination.getHash()))
                    .map(
                            destination -> {
                                var identity = new Identity(false);
                                identity.loadPublicKey(destination.getIdentity().getPublicKey());

                                return identity;
                            }
                    )
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Recall last heard app_data for a destination hash.
     *
     * @param destinationHash estination hash as <strong>byte[]</strong>.
     * @return <strong>byte[]</strong> containing app_data, or <strong>null</strong> if the destination is unknown.
     */
    public static byte[] recallAppData(@NonNull byte[] destinationHash) {
        var key = Hex.encodeHexString(destinationHash);

        return KNOWN_DESTINATIONS.containsKey(key) ? KNOWN_DESTINATIONS.get(key).getAppData() : null;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static final class DestinationData {
        private long timestamp;
        private byte[] packetHash;
        private byte[] publicKey;
        private byte[] appData;
    }

    static int length(String key) {
        return ArrayUtils.getLength(toBytes(key));
    }

    @SneakyThrows
    @JsonIgnore
    static byte[] toBytes(String key) {
        return isNull(key) ? null : Hex.decodeHex(key);
    }
}
