package io.reticulum.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Termination must not block the thread that asks for it.
 * <p>
 * The reference ends the process with {@code os._exit()}, which runs no cleanup.
 * {@code System.exit()} runs every shutdown hook and joins them, so calling it
 * directly deadlocks whenever the caller holds a lock a hook needs. That is
 * exactly what happened in production: an interface teardown reached from
 * {@code Transport.outbound} called exit while holding {@code jobsLock}, and the
 * shutdown hook sat waiting for that same lock — for the remaining hour of the
 * node's life.
 */
class CommonUtilsTerminationTest {

    private java.util.function.IntConsumer savedTerminator;
    private java.util.function.IntConsumer savedHalter;

    @BeforeEach
    void setUp() {
        savedTerminator = CommonUtils.terminator;
        savedHalter = CommonUtils.halter;
        CommonUtils.resetTerminationState();
    }

    @AfterEach
    void tearDown() {
        CommonUtils.terminator = savedTerminator;
        CommonUtils.halter = savedHalter;
        CommonUtils.resetTerminationState();
    }

    @Test
    @DisplayName("exit() returns immediately, so the caller can release its locks")
    void exitDoesNotBlockTheCaller() throws Exception {
        // Reproduces the production shape: the thread asking to terminate holds
        // jobsLock, and a shutdown hook on another thread needs that same lock.
        // System.exit() joins its hooks, so terminating on the calling thread
        // means the lock is never released and the hook never completes.
        var jobsLock = new ReentrantLock();
        var hookAcquiredLock = new CountDownLatch(1);
        var terminationFinished = new CountDownLatch(1);
        var code = new AtomicInteger(-1);

        CommonUtils.terminator = c -> {
            var hook = new Thread(() -> {
                try {
                    if (jobsLock.tryLock(5, TimeUnit.SECONDS)) {
                        jobsLock.unlock();
                        hookAcquiredLock.countDown();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "fake-shutdown-hook");
            hook.start();
            try {
                hook.join();          // what ApplicationShutdownHooks does
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            code.set(c);
            terminationFinished.countDown();
        };

        jobsLock.lock();
        try {
            CommonUtils.exit();
        } finally {
            jobsLock.unlock();
        }

        assertTrue(terminationFinished.await(15, TimeUnit.SECONDS), "termination never completed");
        assertEquals(0, hookAcquiredLock.getCount(),
                "the shutdown hook never got the lock — this is the production deadlock");
        assertEquals(0, code.get());
    }

    @Test
    @DisplayName("panic() terminates with 255")
    void panicUsesCode255() throws Exception {
        var seen = new CountDownLatch(1);
        var code = new AtomicInteger(-1);
        CommonUtils.terminator = c -> {
            code.set(c);
            seen.countDown();
        };

        CommonUtils.panic();

        assertTrue(seen.await(5, TimeUnit.SECONDS));
        assertEquals(255, code.get());
    }

    @Test
    @DisplayName("repeated requests terminate once")
    void terminationIsRequestedOnlyOnce() throws Exception {
        var calls = new AtomicInteger();
        CommonUtils.terminator = c -> calls.incrementAndGet();

        CommonUtils.exit();
        CommonUtils.exit();
        CommonUtils.panic();
        Thread.sleep(300);

        assertEquals(1, calls.get(), "exit must be attempted once, not per caller");
    }
}
