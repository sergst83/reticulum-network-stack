package io.reticulum.packet;

import io.reticulum.constant.ReticulumConstant;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

import static org.apache.commons.lang3.ArrayUtils.subarray;

@EqualsAndHashCode(callSuper = true)
@Data
public class ProofDestination extends Destination {
    private byte[] hash;
    private DestinationType type;

    public ProofDestination(@NonNull Packet packet) {
        hash = subarray(packet.getHash(), 0, ReticulumConstant.TRUNCATED_HASHLENGTH / 8);
        type = DestinationType.SINGLE;
    }

    public byte[] encrypt(byte[] plaintext) {
        return plaintext;
    }
}
