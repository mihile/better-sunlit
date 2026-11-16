package dev.sunlit.bettersunlit.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Moves non-GL startup cleanup work from {@code FMLLoadCompleteEvent.enqueueWork} off the main
 * render thread to a low-priority daemon thread after a brief quiet delay.
 */
public final class AllTheLeaksPerf {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

    private AllTheLeaksPerf() {
    }

    public static CompletableFuture<Void> deferJarCleanup(Runnable work) {
        return deferJarCleanup(work, 10_000L);
    }

    public static CompletableFuture<Void> deferJarCleanup(Runnable work, long delayMs) {
        if (work == null) {
            return CompletableFuture.completedFuture(null);
        }
        Thread worker = new Thread(() -> {
            if (delayMs > 0L) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                work.run();
            } catch (Throwable ignored) {
                // Keep cleanup best-effort and non-fatal on daemon shutdown.
            }
        }, "BetterSunlit-ATL-JarCleanup-" + THREAD_COUNTER.getAndIncrement());
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY + 1);
        worker.start();
        return CompletableFuture.completedFuture(null);
    }
}
