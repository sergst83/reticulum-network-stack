package io.reticulum.link;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs request handlers off the thread that received the request.
 * <p>
 * A request arrives on an interface's I/O thread. Running its handler there
 * stalls every other packet on that interface for as long as the handler takes,
 * and deadlocks a handler that itself waits for Reticulum traffic arriving on
 * the same interface — an rngit fork from an {@code rns://} source does exactly
 * that. The reference no longer handles inbound traffic on the interface thread
 * either: 1.5 moved it to transport worker threads.
 * <p>
 * Bounded so a flood of requests cannot grow threads or memory without limit; a
 * request refused here is dropped and the requester times out, as when a
 * packet is lost. Sized by {@code -Dio.reticulum.requestHandlerThreads}
 * (default 8) and {@code -Dio.reticulum.requestHandlerQueue} (default 256).
 */
@Slf4j
final class RequestHandlerExecutor {

    private static final int THREADS = Math.max(1, Integer.getInteger("io.reticulum.requestHandlerThreads", 8));
    private static final int QUEUE = Math.max(1, Integer.getInteger("io.reticulum.requestHandlerQueue", 256));

    private static final ThreadPoolExecutor EXECUTOR;

    static {
        var counter = new AtomicInteger();
        EXECUTOR = new ThreadPoolExecutor(THREADS, THREADS, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE),
                runnable -> {
                    var thread = new Thread(runnable, "rns-request-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private RequestHandlerExecutor() {
    }

    /** @return false if the request was refused because the executor is saturated */
    static boolean submit(Runnable task) {
        try {
            EXECUTOR.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    log.error("Error while handling request", t);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("Request handler queue full ({} queued, {} threads), dropping request", QUEUE, THREADS);
            return false;
        }
    }
}
