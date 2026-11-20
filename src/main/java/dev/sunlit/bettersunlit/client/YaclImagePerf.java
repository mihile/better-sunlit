package dev.sunlit.bettersunlit.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Offloads YACL's animated image preloading off startup and ResourceReload critical paths so
 * {@code AnimatedDynamicTextureImage.createFromImageReader} does not stall {@code LoadingOverlay}
 * or contend with cold-launch GC before the title screen appears.
 */
public final class YaclImagePerf {
    private static final Logger LOGGER = LoggerFactory.getLogger("BetterSunlit-Perf");
    private static final Object DEFER_LOCK = new Object();
    private static final List<Supplier<CompletableFuture<Void>>> PENDING_TASKS = new ArrayList<>(4);
    private static volatile boolean startupComplete;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BetterSunlit-YACL-Images");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private YaclImagePerf() {
    }

    public static void runAsync(Supplier<CompletableFuture<Void>> task) {
        if (task == null) {
            return;
        }
        if (!startupComplete) {
            synchronized (DEFER_LOCK) {
                if (!startupComplete) {
                    PENDING_TASKS.add(task);
                    return;
                }
            }
        }
        dispatch(task);
    }

    public static void triggerPostStartupIfPending() {
        List<Supplier<CompletableFuture<Void>>> drained = null;
        synchronized (DEFER_LOCK) {
            if (!startupComplete) {
                startupComplete = true;
            }
            if (!PENDING_TASKS.isEmpty()) {
                drained = new ArrayList<>(PENDING_TASKS);
                PENDING_TASKS.clear();
            }
        }
        if (drained != null) {
            for (int i = 0, n = drained.size(); i < n; i++) {
                dispatch(drained.get(i));
            }
        }
    }

    private static void dispatch(Supplier<CompletableFuture<Void>> task) {
        EXECUTOR.execute(() -> {
            try {
                CompletableFuture<Void> future = task.get();
                if (future != null) {
                    future.exceptionally(ex -> {
                        LOGGER.warn("Background YACL image preload failed", ex);
                        return null;
                    });
                }
            } catch (Throwable t) {
                LOGGER.warn("Background YACL image preload failed", t);
            }
        });
    }

    public static ExecutorService executor() {
        return EXECUTOR;
    }
}
