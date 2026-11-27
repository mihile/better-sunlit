package dev.sunlit.bettersunlit.perf;

import java.security.KeyPair;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Defers Spark viewer WebSocket {@link KeyPair} generation past cold startup while guaranteeing
 * synchronous completion if a caller queries local keys early.
 */
public final class SparkKeyStorePerf {
    private static final long DEFAULT_DELAY_SECONDS = 60L;
    private static final Map<CompletableFuture<KeyPair>, Supplier<KeyPair>> PENDING =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "BetterSunlit-SparkKeyGen");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });

    private SparkKeyStorePerf() {
    }

    public static CompletableFuture<KeyPair> deferKeyPair(Supplier<KeyPair> supplier) {
        return deferKeyPair(supplier, DEFAULT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    static CompletableFuture<KeyPair> deferKeyPair(Supplier<KeyPair> supplier, long delay, TimeUnit unit) {
        CompletableFuture<KeyPair> future = new CompletableFuture<>();
        if (supplier == null) {
            future.complete(null);
            return future;
        }
        PENDING.put(future, supplier);
        SCHEDULER.schedule(() -> completeIfNeeded(future), Math.max(0L, delay), unit);
        return future;
    }

    public static void ensureCompleted(CompletableFuture<KeyPair> future) {
        if (future == null || future.isDone()) {
            return;
        }
        completeIfNeeded(future);
    }

    public static boolean isPending(CompletableFuture<KeyPair> future) {
        return future != null && PENDING.containsKey(future);
    }

    private static void completeIfNeeded(CompletableFuture<KeyPair> future) {
        Supplier<KeyPair> supplier = PENDING.remove(future);
        if (supplier == null || future.isDone()) {
            return;
        }
        try {
            future.complete(supplier.get());
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }
}
