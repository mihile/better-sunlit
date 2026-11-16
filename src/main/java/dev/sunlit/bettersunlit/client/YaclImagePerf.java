package dev.sunlit.bettersunlit.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Offloads YACL's animated image preloading off the ResourceReload critical path so
 * {@code AnimatedDynamicTextureImage.createFromImageReader} does not stall {@code LoadingOverlay}.
 */
public final class YaclImagePerf {
    private static final Logger LOGGER = LoggerFactory.getLogger("BetterSunlit-Perf");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BetterSunlit-YACL-Images");
        t.setDaemon(true);
        return t;
    });

    private YaclImagePerf() {
    }

    public static void runAsync(Supplier<CompletableFuture<Void>> task) {
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
