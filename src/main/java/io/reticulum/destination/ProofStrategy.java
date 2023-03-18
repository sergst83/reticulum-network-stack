package io.reticulum.destination;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProofStrategy {
    PROVE_NONE((byte) 0x00),
    PROVE_APP((byte) 0x22),
    PROVE_ALL((byte) 0x23);

    private final byte value;
}
