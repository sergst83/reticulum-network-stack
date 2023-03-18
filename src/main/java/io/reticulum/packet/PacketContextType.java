package io.reticulum.packet;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PacketContextType {
    /**
     * Generic data packet
     */
    NONE((byte) 0x00),
    /**
     * Packet is part of a resource
     */
    RESOURCE((byte) 0x01),
    /**
     * Packet is a resource advertisement
     */
    RESOURCE_ADV((byte) 0x02),
    /**
     * Packet is a resource part request
     */
    RESOURCE_REQ((byte) 0x03),
    /**
     * Packet is a resource hashmap update
     */
    RESOURCE_HMU((byte) 0x04),
    /**
     * Packet is a resource proof
     */
    RESOURCE_PRF((byte) 0x05),
    /**
     * Packet is a resource initiator cancel message
     */
    RESOURCE_ICL((byte) 0x06),
    /**
     * Packet is a resource receiver cancel message
     */
    RESOURCE_RCL((byte) 0x07),
    /**
     * Packet is a cache request
     */
    CACHE_REQUEST((byte) 0x08),
    /**
     * Packet is a request
     */
    REQUEST((byte) 0x09),
    /**
     * Packet is a response to a request
     */
    RESPONSE((byte) 0x0A),
    /**
     * Packet is a response to a path request
     */
    PATH_RESPONSE((byte) 0x0B),
    /**
     * Packet is a command
     */
    COMMAND((byte) 0x0C),
    /**
     * Packet is a status of an executed command
     */
    COMMAND_STATUS((byte) 0x0D),
    /**
     * Packet contains link channel data
     */
    CHANNEL((byte) 0x0E),
    /**
     * Packet is a keepalive packet
     */
    KEEPALIVE((byte) 0xFA),
    /**
     * Packet is a link peer identification proof
     */
    LINKIDENTIFY((byte) 0xFB),
    /**
     * Packet is a link close message
     */
    LINKCLOSE((byte) 0xFC),
    /**
     * Packet is a link packet proof
     */
    LINKPROOF((byte) 0xFD),
    /**
     * Packet is a link request round-trip time measurement
     */
    LRRTT((byte) 0xFE),
    /**
     * Packet is a link request proof
     */
    LRPROOF((byte) 0xFF),
    ;

    private final byte value;
}
