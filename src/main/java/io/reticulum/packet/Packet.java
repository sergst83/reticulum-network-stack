package io.reticulum.packet;

import io.reticulum.Transport;
import io.reticulum.constant.ReticulumConstant;
import io.reticulum.destination.AbstractDestination;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;
import io.reticulum.packet.data.Addresses;
import io.reticulum.packet.data.DataPacket;
import io.reticulum.packet.data.DataPacketConverter;
import io.reticulum.packet.data.Flags;
import io.reticulum.packet.data.Header;
import io.reticulum.transport.TransportType;
import lombok.Data;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

import static io.reticulum.constant.ReticulumConstant.TRUNCATED_HASHLENGTH;
import static io.reticulum.destination.DestinationType.LINK;
import static io.reticulum.packet.HeaderType.HEADER_1;
import static io.reticulum.packet.HeaderType.HEADER_2;
import static io.reticulum.packet.PacketContextType.CACHE_REQUEST;
import static io.reticulum.packet.PacketContextType.KEEPALIVE;
import static io.reticulum.packet.PacketContextType.LRPROOF;
import static io.reticulum.packet.PacketContextType.RESOURCE;
import static io.reticulum.packet.PacketContextType.RESOURCE_PRF;
import static io.reticulum.packet.PacketType.ANNOUNCE;
import static io.reticulum.packet.PacketType.LINKREQUEST;
import static io.reticulum.packet.PacketType.PROOF;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static io.reticulum.utils.IdentityUtils.truncatedHash;
import static java.math.BigInteger.ONE;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.ArrayUtils.subarray;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

/**
 * <a href=https://reticulum.network/manual/understanding.html#wire-format>https://reticulum.network/manual/understanding.html#wire-format</a>
 *
 * <pre>
 * {@code
 * == Reticulum Wire Format ======
 *
 * A Reticulum packet is composed of the following fields:
 *
 * [HEADER 2 bytes] [ADDRESSES 16/32 bytes] [CONTEXT 1 byte] [DATA 0-465 bytes]
 *
 * * The HEADER field is 2 bytes long.
 *   * Byte 1: [IFAC Flag], [Header Type], [Propagation Type], [Destination Type] and [Packet Type]
 *   * Byte 2: Number of hops
 *
 * * Interface Access Code field if the IFAC flag was set.
 *   * The length of the Interface Access Code can vary from
 *     1 to 64 bytes according to physical interface
 *     capabilities and configuration.
 *
 * * The ADDRESSES field contains either 1 or 2 addresses.
 *   * Each address is 16 bytes long.
 *   * The Header Type flag in the HEADER field determines
 *     whether the ADDRESSES field contains 1 or 2 addresses.
 *   * Addresses are SHA-256 hashes truncated to 16 bytes.
 *
 * * The CONTEXT field is 1 byte.
 *   * It is used by Reticulum to determine packet context.
 *
 * * The DATA field is between 0 and 465 bytes.
 *   * It contains the packets data payload.
 *
 * IFAC Flag
 * -----------------
 * open             0  Packet for publically accessible interface
 * authenticated    1  Interface authentication is included in packet
 *
 *
 * Header Types
 * -----------------
 * type 1           0  Two byte header, one 16 byte address field
 * type 2           1  Two byte header, two 16 byte address fields
 *
 *
 * Propagation Types
 * -----------------
 * broadcast       00
 * transport       01
 * reserved        10
 * reserved        11
 *
 *
 * Destination Types
 * -----------------
 * single          00
 * group           01
 * plain           10
 * link            11
 *
 *
 * Packet Types
 * -----------------
 * data            00
 * announce        01
 * link request    10
 * proof           11
 *
 *
 * +- Packet Example -+
 *
 *    HEADER FIELD           DESTINATION FIELDS            CONTEXT FIELD  DATA FIELD
 *  _______|_______   ________________|________________   ________|______   __|_
 * |               | |                                 | |               | |    |
 * 01010000 00000100 [HASH1, 16 bytes] [HASH2, 16 bytes] [CONTEXT, 1 byte] [DATA]
 * || | | |    |
 * || | | |    +-- Hops             = 4
 * || | | +------- Packet Type      = DATA
 * || | +--------- Destination Type = SINGLE
 * || +----------- Propagation Type = TRANSPORT
 * |+------------- Header Type      = HEADER_2 (two byte header, two address fields)
 * +-------------- Access Codes     = DISABLED
 *
 *
 * +- Packet Example -+
 *
 *    HEADER FIELD   DESTINATION FIELD   CONTEXT FIELD  DATA FIELD
 *  _______|_______   _______|_______   ________|______   __|_
 * |               | |               | |               | |    |
 * 00000000 00000111 [HASH1, 16 bytes] [CONTEXT, 1 byte] [DATA]
 * || | | |    |
 * || | | |    +-- Hops             = 7
 * || | | +------- Packet Type      = DATA
 * || | +--------- Destination Type = SINGLE
 * || +----------- Propagation Type = BROADCAST
 * |+------------- Header Type      = HEADER_1 (two byte header, one address field)
 * +-------------- Access Codes     = DISABLED
 *
 *
 * +- Packet Example -+
 *
 *    HEADER FIELD     IFAC FIELD    DESTINATION FIELD   CONTEXT FIELD  DATA FIELD
 *  _______|_______   ______|______   _______|_______   ________|______   __|_
 * |               | |             | |               | |               | |    |
 * 10000000 00000111 [IFAC, N bytes] [HASH1, 16 bytes] [CONTEXT, 1 byte] [DATA]
 * || | | |    |
 * || | | |    +-- Hops             = 7
 * || | | +------- Packet Type      = DATA
 * || | +--------- Destination Type = SINGLE
 * || +----------- Propagation Type = BROADCAST
 * |+------------- Header Type      = HEADER_1 (two byte header, one address field)
 * +-------------- Access Codes     = ENABLED
 *
 *
 * Size examples of different packet types
 * ---------------------------------------
 *
 * The following table lists example sizes of various
 * packet types. The size listed are the complete on-
 * wire size counting all fields including headers,
 * but excluding any interface access codes.
 *
 * - Path Request    :    51  bytes
 * - Announce        :    167 bytes
 * - Link Request    :    83  bytes
 * - Link Proof      :    115 bytes
 * - Link RTT packet :    99  bytes
 * - Link keepalive  :    20  bytes
 * }
 * </pre>
 */
@Data
@Slf4j
public class Packet {

    private Instant sentAt;
    private boolean fromPacked;
    private boolean createReceipt;
    private boolean sent;
    private boolean packed;
    private Flags flags;
    private PacketContextType context;
    private byte[] destinationHash;
    private byte[] data;

    private Integer rssi;
    private Integer snr;
    private byte[] transportId;
    private int hops;
    private ConnectionInterface attachedInterface;
    private ConnectionInterface receivingInterface;
    private byte[] packetHash;
    private AbstractDestination destination;
    private byte[] raw;
    private byte[] plaintext;
    private TransportType transportType = TransportType.BROADCAST;
    private HeaderType headerType = HEADER_1;
    private PacketType packetType = PacketType.DATA;
    private DestinationType destinationType;
    private PacketReceipt receipt;
    private int mtu;
    private byte[] ciphertext;

    private byte[] mapHash;

    public Packet(ConnectionInterface attachedInterface) {
        this.attachedInterface = attachedInterface;
        this.mtu = ReticulumConstant.MTU;
    }

    public Packet(Destination destination, byte[] data, PacketType packetType, PacketContextType context, ConnectionInterface attachedInterface, boolean createReceipt) {
        this(attachedInterface);
        this.packetType = packetType;
        this.destination = destination;
        this.context = context;
        this.data = data;
        this.flags = getPackedFlags();
        this.createReceipt = createReceipt;
    }

    public Packet(Destination destination, byte[] data, PacketType packetType, PacketContextType context, ConnectionInterface attachedInterface) {
        this(destination, data, packetType, context, attachedInterface, true);
    }

    public Packet(Destination destination, byte[] proofData, PacketType proof, ConnectionInterface attachedInterface) {
        this(destination, proofData, proof, null, attachedInterface);
    }

    public Packet(Destination destination, byte[] requestData, PacketType linkRequest) {
        this(destination, requestData, linkRequest, null);
    }


    public Packet(Link link, byte[] data, PacketType packetType, PacketContextType contextType) {
        this(null);
        this.raw = data;
        this.packed = true;
        this.fromPacked = true;
        this.createReceipt = false;
    }

    public Packet(Link link, byte[] data, PacketType packetType) {
        this(link, data, packetType, null);
    }

    public Packet(Link link, byte[] data, PacketContextType packetContextType) {
        this(link, data, null, packetContextType);
    }

    @SneakyThrows
    public synchronized void pack() {
        destinationHash = destination.getHash();
        var packetData = new DataPacket();
        var header = new Header(packetData);
        var address = new Addresses(packetData);
        header.setFlags(flags);
        header.setHops((byte) hops);
        if (context == LRPROOF) {
            address.setHash1(destination.getHash());
            ciphertext = this.data;
        } else {
            switch (headerType) {
                case HEADER_1:
                    address.setHash1(destination.getHash());
                    if (packetType == ANNOUNCE) {
                        //Announce packets are not encrypted
                        ciphertext = data;
                    } else if (packetType == LINKREQUEST) {
                        //Link request packets are not encrypted
                        ciphertext = data;
                    } else if (packetType == PROOF && context == RESOURCE_PRF) {
                        //Resource proofs are not encrypted
                        ciphertext = data;
                    } else if (packetType == PROOF && destination.getType() == LINK) {
                        //Packet proofs over links are not encrypted
                        ciphertext = data;
                    } else if (context == RESOURCE) {
                        //A resource takes care of symmetric encryption by itself
                        ciphertext = data;
                    } else if (context == KEEPALIVE) {
                        //Keepalive packets contain no actual data
                        ciphertext = data;
                    } else if (context == CACHE_REQUEST) {
                        //Cache-requests are not encrypted
                        ciphertext = data;
                    } else {
                        //In all other cases, we encrypt the packet with the destination's encryption method
                        ciphertext = destination.encrypt(data);
                    }
                    break;
                case HEADER_2:
                    if (nonNull(transportId)) {
                        address.setHash1(transportId);
                        address.setHash2(destination.getHash());

                        if (packetType == ANNOUNCE) {
                            //Announce packets are not encrypted
                            ciphertext = data;
                        }
                    } else {
                        throw new IllegalStateException("Packet with header type 2 must have a transport ID");
                    }
                    break;
            }
        }

        packetData.setContext(context);
        packetData.setData(ciphertext);

        raw = DataPacketConverter.toBytes(packetData);

        if (raw.length > mtu) {
            throw new IllegalStateException(String.format("Packet size of %s  exceeds MTU of %s bytes", raw.length, mtu));
        }

        packed = true;
        updateHash();
    }

    public synchronized boolean unpack() {
        try {
            var packetData = DataPacketConverter.fromBytes(raw);
            var header = packetData.getHeader();
            var flags = header.getFlags();
            headerType = flags.getHeaderType();
            transportType = flags.getPropagationType();
            destinationType = flags.getDestinationType();
            packetType = flags.getPacketType();

            var addresses = packetData.getAddresses();
            if (headerType == HEADER_2) {
                transportId = addresses.getHash1();
                destinationHash = addresses.getHash2();
            } else {
                transportId = null;
                destinationHash = addresses.getHash1();
            }
            context = packetData.getContext();
            data = packetData.getData();

            packed = false;
            updateHash();

            return true;
        } catch (Exception e) {
            log.error("Received malformed packet, dropping it.", e);
            return false;
        }
    }

    public synchronized byte[] getHash() {
        return truncatedHash(getHashablePart());
    }

    public ProofDestination generatrProofDestination() {
        return new ProofDestination(this);
    }

    /**
     * Sends the packet.
     *
     * @return {@link PacketReceipt} instance if <strong>createReceipt</strong> was set to <strong>true</strong> when the packet
     * was instantiated, if not returns <strong>null</strong>. If the packet could not be sent <strong>false</strong> is returned.
     */
    public synchronized PacketReceipt send() {
        if (isFalse(sent)) {
            if (destination.getType() == LINK) {
                var dest = (Link) destination;
                if (dest.getStatus() == LinkStatus.CLOSED) {
                    throw new IllegalStateException("Attempt to transmit over a closed link");
                } else {
                    dest.setLastOutbound(Instant.now());
                    dest.setTx(dest.getTx().add(ONE));
                    dest.setTxBytes(dest.getTxBytes().add(BigInteger.valueOf(data.length)));
                }
            }

            if (isFalse(packed)) {
                pack();
            }

            if (Transport.getInstance().outbound(this)) {
                return receipt;
            } else {
                log.error("No interfaces could process the outbound packet");
                sent = false;
                receipt = null;
            }
        } else {
            throw new IllegalStateException("Packet was already sent");
        }

        return null;
    }

    /**
     * Re-sends the packet.
     *
     * @return {@link PacketReceipt} instance if <strong>createReceipt</strong> was set to <strong>true</strong> when the packet
     * was instantiated, if not returns <strong>null</strong>. If the packet could not be sent <strong>false</strong> is returned.
     */
    public synchronized PacketReceipt resend() {
        if (sent) {
            if (Transport.getInstance().outbound(this)) {
                return receipt;
            } else {
                log.error("No interfaces could process the outbound packet");
                sent = false;
                receipt = null;

                return null;
            }
        } else {
            throw new IllegalStateException("Packet was not sent yet");
        }
    }

    public void prove(final AbstractDestination destination) {
        if (fromPacked) {
            if (requireNonNull(destination.getType()) == LINK) {
                ((Link) this.destination).provePacket(this);
            } else {
                var dest = (Destination) this.destination;
                if (nonNull(dest.getIdentity()) && nonNull(dest.getIdentity().getPrv())) {
                    dest.getIdentity().prove(this, destination);
                }
            }
        } else {
            log.error("Could not prove packet associated with neither a destination nor a link");
        }
    }

    /**
     * Generates a special destination that allows Reticulum to direct the proof back to the proved packet's sender
     *
     * @return {@link ProofDestination}
     */
    public ProofDestination generateProofDestination() {
        return new ProofDestination(this);
    }

    public synchronized boolean validateProofPacket(Packet proofPacket) {
        return receipt.validateProofPacket(proofPacket);
    }

    public synchronized boolean validateProof(@NonNull final byte[] proof) {
        return receipt.validateProof(proof, null);
    }

    public byte[] getTruncatedHash() {
        return truncatedHash(getHashablePart());
    }

    private void updateHash() {
        packetHash = getHash();
    }

    @SneakyThrows
    private byte[] getHashablePart() {
        var hashablePart = new byte[]{(byte) (raw[0] & 0b00001111)};
        if (headerType == HEADER_2) {
            hashablePart = concatArrays(hashablePart, subarray(raw, TRUNCATED_HASHLENGTH / 8 + 2, raw.length));
        } else {
            hashablePart = concatArrays(hashablePart, subarray(raw, 2, raw.length));
        }

        return hashablePart;
    }

    /**
     * Двоичные влаги в виде числа
     *
     * @return флаги в виде
     */
    private Flags getPackedFlags() {
        var flags = new Flags();
        flags.setHeaderType(headerType);
        flags.setPropagationType(transportType);
        flags.setPacketType(packetType);
        if (context == LRPROOF) {
            flags.setDestinationType(LINK);
        } else {
            flags.setDestinationType(destination.getType());
        }

        return flags;
    }

    public Link getLink() {
        return (Link) Optional.ofNullable(destination)
                .filter(dest -> dest.getType() == LINK)
                .orElse(null);
    }
}
