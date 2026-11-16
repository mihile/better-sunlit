package dev.sunlit.bettersunlit.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.watermedia.WaterMedia;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Moves {@link WaterMedia#start()} (including native LibVLC discovery/initialization) off
 * {@code modloading-worker-0} onto a background daemon thread, awaiting completion on demand.
 */
public final class WaterMediaPerf {
    private static final Logger LOGGER = LoggerFactory.getLogger("BetterSunlit-Perf");
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final ThreadLocal<Boolean> IN_LOADER_THREAD = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static volatile CountDownLatch readyLatch = new CountDownLatch(1);

    private WaterMediaPerf() {
    }

    public static void startAsync(WaterMedia waterMedia) {
        if (waterMedia == null) {
            return;
        }
        if (!STARTED.compareAndSet(false, true)) {
            awaitStarted();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        readyLatch = latch;
        Thread thread = new Thread(() -> {
            IN_LOADER_THREAD.set(Boolean.TRUE);
            try {
                waterMedia.start();
            } catch (Throwable t) {
                LOGGER.error("Background WaterMedia initialization failed", t);
            } finally {
                IN_LOADER_THREAD.remove();
                latch.countDown();
            }
        }, "BetterSunlit-WaterMedia");
        thread.setDaemon(true);
        thread.start();
    }

    public static void awaitStarted() {
        if (!STARTED.get() || Boolean.TRUE.equals(IN_LOADER_THREAD.get())) {
            return;
        }
        CountDownLatch latch = readyLatch;
        if (latch.getCount() == 0L) {
            return;
        }
        boolean interrupted = false;
        try {
            if (!latch.await(20L, TimeUnit.SECONDS)) {
                LOGGER.warn("Timed out waiting for background WaterMedia initialization");
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
