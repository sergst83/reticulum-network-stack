package io.reticulum.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.Executors.defaultThreadFactory;
import static java.util.concurrent.Executors.newScheduledThreadPool;

@UtilityClass
@Slf4j
public class Scheduler {
    public static final ScheduledExecutorService scheduler =
            newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2);

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
        defaultThreadFactory().newThread(() -> {
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
    }
}
