package io.reticulum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.reticulum.constant.TransportConstant.HASHLIST_MAXSIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packet dedup window rotates rather than clearing, matching
 * {@code RNS/Transport.py:831-834}.
 * <p>
 * The old table cleared outright at {@code HASHLIST_MAXSIZE}, dropping the entire
 * window in one go and letting every duplicate through until it refilled. The
 * reference keeps two generations and culls the current one at half the maximum, so
 * the pair always covers a full window's worth of history.
 * <p>
 * Modelled here rather than driven through {@code Transport}, whose singleton needs
 * a Reticulum instance; the invariants under test are the rotation policy itself.
 */
class TransportDedupWindowTest {

    /** The production policy: rotate the current window into prev at maxsize/2. */
    private static final class Window {
        Set<String> current = ConcurrentHashMap.newKeySet();
        Set<String> prev = ConcurrentHashMap.newKeySet();

        void add(String hash) {
            current.add(hash);
        }

        boolean seen(String hash) {
            return current.contains(hash) || prev.contains(hash);
        }

        void cullIfNeeded() {
            if (current.size() > HASHLIST_MAXSIZE / 2) {
                prev = current;
                current = ConcurrentHashMap.newKeySet();
            }
        }
    }

    @Test
    @DisplayName("rotation keeps the previous generation visible")
    void rotationPreservesHistory() {
        var w = new Window();
        var early = new HashSet<String>();

        for (var i = 0; i <= HASHLIST_MAXSIZE / 2; i++) {
            var h = "hash-" + i;
            w.add(h);
            if (i < 10) {
                early.add(h);
            }
        }
        w.cullIfNeeded();

        // The current set has just been emptied by the rotation...
        assertTrue(w.current.isEmpty());
        // ...but everything it held is still deduplicated via prev. Clearing outright
        // would have let all of these through again.
        for (var h : early) {
            assertTrue(w.seen(h), h + " must still be recognised after rotation");
        }
    }

    @Test
    @DisplayName("history is only dropped after two rotations, a full window later")
    void historyExpiresAfterTwoRotations() {
        var w = new Window();
        w.add("oldest");

        for (var round = 0; round < 2; round++) {
            for (var i = 0; i <= HASHLIST_MAXSIZE / 2; i++) {
                w.add("r" + round + "-" + i);
            }
            w.cullIfNeeded();
        }

        assertFalse(w.seen("oldest"), "an entry two rotations old has aged out, as intended");
    }

    @Test
    @DisplayName("the cull threshold is half the maximum, as in the reference")
    void cullsAtHalfTheMaximum() {
        var w = new Window();
        for (var i = 0; i <= HASHLIST_MAXSIZE / 2; i++) {
            w.add("hash-" + i);
        }

        assertEquals(HASHLIST_MAXSIZE / 2 + 1, w.current.size());
        w.cullIfNeeded();
        assertEquals(0, w.current.size(), "must rotate at maxsize/2, not maxsize");
        assertEquals(HASHLIST_MAXSIZE / 2 + 1, w.prev.size());
    }
}
