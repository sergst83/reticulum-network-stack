package io.reticulum.transport;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A single entry in the blackhole table.
 *
 * <p>Mirrors the dict {@code {"source": ..., "until": ..., "reason": ...}}
 * used in Python's {@code Transport.blackholed_identities}.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BlackholeEntry {

    /**
     * Identity hash (as hex string) of the transport node that added this entry.
     * For locally-added entries this equals the local transport identity hash.
     */
    private String source;

    /**
     * Optional expiry time.  When non-null the blackhole is automatically
     * lifted once {@code Instant.now()} is after this value.
     * {@code null} means the blackhole never expires automatically.
     */
    private Instant until;

    /**
     * Optional human-readable reason for blackholing this identity.
     */
    private String reason;
}
