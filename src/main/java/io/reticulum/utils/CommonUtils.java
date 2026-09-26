package io.reticulum.utils;

import lombok.NoArgsConstructor;
import org.apache.commons.lang3.ArrayUtils;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

import static lombok.AccessLevel.PRIVATE;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

@NoArgsConstructor(access = PRIVATE)
public final class CommonUtils {

    /**
     * Grace period given to shutdown hooks before the JVM is halted outright.
     */
    private static final long SHUTDOWN_GRACE_MS = 30_000;

    private static final AtomicBoolean TERMINATION_REQUESTED = new AtomicBoolean();

    /**
     * How termination is actually performed. Replaced in tests, which cannot
     * call the real thing without taking the test JVM with them.
     */
    static volatile IntConsumer terminator = System::exit;

    /** Last-resort termination, bypassing shutdown hooks. */
    static volatile IntConsumer halter = code -> Runtime.getRuntime().halt(code);

    public static void panic() {
        terminate(255);
    }

    public static void exit() {
        terminate(0);
    }

    /**
     * Terminate the process, without blocking the calling thread.
     * <p>
     * The indirection matters. The reference ends the process with
     * {@code os._exit()} ({@code RNS.exit}), which terminates immediately and
     * runs no cleanup handlers. {@code System.exit()} is the opposite: it runs
     * every registered shutdown hook and <em>joins</em> them before the JVM
     * goes down. Called directly, as this used to be, it deadlocks whenever the
     * caller holds a lock that a shutdown hook needs — and the one caller is an
     * interface teardown reached from {@code Transport.outbound}, which holds
     * {@code jobsLock}:
     * <pre>
     *   interface thread   holds jobsLock, in System.exit() joining hooks
     *   shutdown hook      sending link-close packets, waiting for jobsLock
     * </pre>
     * Observed in production: a node that lost its shared instance sat in that
     * circular wait for the remaining hour of its life, with every other
     * Transport caller spin-waiting behind the lock.
     * <p>
     * Terminating from a separate thread lets the caller return and drop its
     * locks, so the hooks can complete and the host application still gets to
     * flush its own state — which {@code os._exit()} would not allow. A daemon
     * watchdog then halts the JVM if the hooks stall anyway, preserving the one
     * guarantee {@code os._exit()} does give: that the process actually dies.
     */
    private static void terminate(int code) {
        if (isFalse(TERMINATION_REQUESTED.compareAndSet(false, true))) {
            return;
        }

        var exiter = new Thread(() -> {
            var watchdog = new Thread(() -> {
                try {
                    Thread.sleep(SHUTDOWN_GRACE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    return;
                }
                halter.accept(code);
            }, "reticulum-exit-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            terminator.accept(code);
        }, "reticulum-exit");
        exiter.setDaemon(false);
        exiter.start();
    }

    /** Visible for testing: forget that termination was requested. */
    static void resetTerminationState() {
        TERMINATION_REQUESTED.set(false);
    }

    public static byte[] longToByteArray(long value, int arraySize) {
        var result = new byte[arraySize];
        var valArray = BigInteger.valueOf(value).toByteArray();
        for (int i = 0; i < valArray.length && i < result.length; i++) {
            result[i] = valArray[i];
        }
        if (valArray.length < result.length) {
            ArrayUtils.shift(result, result.length - valArray.length);
        }

        return result;
    }
}
