package dev.sunlit.bettersunlit.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates off-thread initialization for Emojiful's GitHub emoji list downloads so
 * {@code ClientEmojiHandler.setup()} never blocks the Render thread during {@code Minecraft.<init>}.
 */
public final class EmojifulAsyncPerf {
    private static final Logger LOGGER = LoggerFactory.getLogger("BetterSunlit-Perf");
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final ThreadLocal<Boolean> IS_LOADER_THREAD = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static volatile CountDownLatch readyLatch = new CountDownLatch(1);

    private EmojifulAsyncPerf() {
    }

    public static void startAsync(Runnable loadTask, Runnable finishOnRenderThread) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        readyLatch = latch;
        Thread thread = new Thread(() -> {
            IS_LOADER_THREAD.set(Boolean.TRUE);
            try {
                loadTask.run();
            } catch (Throwable t) {
                LOGGER.warn("Background Emojiful initialization failed", t);
            } finally {
                IS_LOADER_THREAD.remove();
                latch.countDown();
            }
            if (finishOnRenderThread != null) {
                finishOnRenderThread.run();
            }
        }, "BetterSunlit-Emojiful");
        thread.setDaemon(true);
        thread.start();
    }

    public static void awaitLoaded() {
        if (!STARTED.get() || Boolean.TRUE.equals(IS_LOADER_THREAD.get())) {
            return;
        }
        CountDownLatch latch = readyLatch;
        if (latch.getCount() == 0L) {
            return;
        }
        boolean interrupted = false;
        try {
            if (!latch.await(15L, TimeUnit.SECONDS)) {
                LOGGER.warn("Timed out waiting for background Emojiful initialization");
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
