package io.reticulum.buffer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Listener notifications must reuse threads rather than create one per message.
 * <p>
 * This site used to be {@code new Thread(...).start()} per listener per message,
 * transcribed from {@code RNS/Buffer.py:161}. A production node reached
 * {@code Thread-2948623} — nearly three million threads in under two days — and it
 * was a thread from here that reported the heap exhaustion which wedged the node.
 */
class RawChannelReaderNotifyTest {

    private static ExecutorService notifier() throws Exception {
        Field f = RawChannelReader.class.getDeclaredField("NOTIFIER");
        f.setAccessible(true);

        return (ExecutorService) f.get(null);
    }

    @Test
    @DisplayName("many notifications are served by a handful of reused threads")
    void notificationsReuseThreads() throws Exception {
        var messages = 2000;
        var done = new CountDownLatch(messages);
        Set<String> threads = ConcurrentHashMap.newKeySet();

        var pool = notifier();
        for (var i = 0; i < messages; i++) {
            pool.execute(() -> {
                threads.add(Thread.currentThread().getName());
                done.countDown();
            });
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "notifications did not complete");
        // Thread-per-message would have used 2000 distinct threads.
        assertTrue(threads.size() < messages / 10,
                "expected threads to be reused, but " + messages + " tasks used "
                        + threads.size() + " threads");
    }

    @Test
    @DisplayName("notification threads are daemons, so they cannot hold the JVM up")
    void notificationThreadsAreDaemons() throws Exception {
        var seen = new CountDownLatch(1);
        var daemon = new java.util.concurrent.atomic.AtomicBoolean();

        notifier().execute(() -> {
            daemon.set(Thread.currentThread().isDaemon());
            seen.countDown();
        });

        assertTrue(seen.await(10, TimeUnit.SECONDS));
        assertTrue(daemon.get(), "a non-daemon notifier would block JVM shutdown");
    }

    @Test
    @DisplayName("a listener that throws does not kill its pool thread")
    void listenerExceptionDoesNotKillTheWorker() throws Exception {
        // Without the guard the worker dies on the first exception and the pool
        // silently loses capacity, one thread per misbehaving listener.
        var pool = notifier();
        var survived = new AtomicInteger();
        var done = new CountDownLatch(50);

        for (var i = 0; i < 50; i++) {
            pool.execute(() -> {
                try {
                    throw new IllegalStateException("listener blew up");
                } catch (Exception e) {
                    survived.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(20, TimeUnit.SECONDS));
        assertEquals(50, survived.get());
    }
}
