package io.reticulum.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.Executors.newScheduledThreadPool;

@UtilityClass
@Slf4j
public class Scheduler {

    /**
     * Daemon thread factory. The scheduler drives the core Transport.jobs() loop
     * plus periodic persist/announce work; those must never keep the JVM alive
     * once the host application is shutting down. Non-daemon scheduler threads were
     * observed preventing clean process exit (the node "would not stop" and had to
     * be killed) and kept the mesh recovering after shutdown had begun.
     */
    private static ThreadFactory daemonThreadFactory(String namePrefix) {
        var counter = new AtomicInteger();
        return runnable -> {
            var thread = new Thread(runnable, namePrefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static final ScheduledExecutorService scheduler =
            newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2, daemonThreadFactory("rns-scheduler"));

    private static final ThreadFactory watchdogThreadFactory = daemonThreadFactory("rns-scheduler-watchdog");

    public static void scheduleWithFixedDelaySafe(Runnable command, long delay, TimeUnit unite) {
        var future = scheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        command.run();
                    } catch (Throwable t) {
                        // Catch Throwable, not just Exception: OutOfMemoryError and other
                        // Errors otherwise escape this safety net and abort the scheduled
                        // task (ScheduledExecutorService suppresses subsequent executions
                        // on exceptional completion). Swallow so the next tick fires
                        // normally — matches the "safe" intent of this wrapper.
                        // The logger itself may allocate; wrap it so a logger-OOM does
                        // not bypass the swallow.
                        try {
                            log.error("Error while execute task", t);
                        } catch (Throwable ignored) {
                            // Best-effort: if the logger itself fails, drop quietly.
                        }
                    }
                }, 100, delay, unite);

        //watchdog
        var watchdog = watchdogThreadFactory.newThread(() -> {
            while (true) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    //restart
                    log.error("Execution exception. Restarting jod", e);
                    scheduleWithFixedDelaySafe(command, delay, unite);
                    return;
                } catch (InterruptedException e) {
                    log.error("Interrupt exception", e);
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        watchdog.start();
    }

    /**
     * Stops all scheduled work (Transport.jobs, periodic persist/announce, AutoInterface
     * peerAnnounce/peerJob). Called from the Reticulum exit path so the mesh stops
     * recovering once shutdown has begun and no scheduled task can keep the JVM alive.
     * Safe to call more than once.
     */
    public static void shutdown() {
        scheduler.shutdownNow();
    }
}
