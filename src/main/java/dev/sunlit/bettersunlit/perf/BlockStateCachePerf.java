package dev.sunlit.bettersunlit.perf;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Parallelizes ModernFix's {@code BlockStateCacheHandler.invalidateCache} sweep over all
 * registered blockstates (~300k instances) instead of walking {@code IdMapper.iterator()} on
 * a single worker thread during {@code GameData.freezeData}.
 */
public final class BlockStateCachePerf {
    private static final Consumer<Object> CLEAR_CACHE_FN = resolveClearCache();

    private BlockStateCachePerf() {
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Object> resolveClearCache() {
        try {
            Class<?> intf = Class.forName(
                    "org.embeddedt.modernfix.duck.IBlockState",
                    false,
                    BlockStateCachePerf.class.getClassLoader());
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle target = lookup.findVirtual(intf, "clearCache", MethodType.methodType(void.class));
            CallSite site = LambdaMetafactory.metafactory(
                    lookup,
                    "accept",
                    MethodType.methodType(Consumer.class),
                    MethodType.methodType(void.class, Object.class),
                    target,
                    MethodType.methodType(void.class, intf));
            return (Consumer<Object>) site.getTarget().invokeExact();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean invalidateAllParallel(List<?> states) {
        if (CLEAR_CACHE_FN == null || states == null) {
            return false;
        }
        int size = states.size();
        if (size < 4096) {
            return false;
        }
        int workers = Math.min(16, Math.max(2, Runtime.getRuntime().availableProcessors()));
        int chunk = (size + workers - 1) / workers;
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[workers - 1];
        for (int w = 0; w < workers - 1; w++) {
            final int start = (w + 1) * chunk;
            final int end = Math.min(size, start + chunk);
            if (start >= end) {
                continue;
            }
            tasks[w] = CompletableFuture.runAsync(() -> clearSlice(states, start, end));
        }
        clearSlice(states, 0, Math.min(size, chunk));
        for (int w = 0; w < tasks.length; w++) {
            if (tasks[w] != null) {
                tasks[w].join();
            }
        }
        return true;
    }

    private static void clearSlice(List<?> states, int start, int end) {
        final Consumer<Object> fn = CLEAR_CACHE_FN;
        for (int i = start; i < end; i++) {
            Object st = states.get(i);
            if (st != null) {
                fn.accept(st);
            }
        }
    }
}
