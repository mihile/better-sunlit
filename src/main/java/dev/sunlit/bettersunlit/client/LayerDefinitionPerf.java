package dev.sunlit.bettersunlit.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Evaluates pure-CPU entity {@code LayerDefinition} suppliers in parallel across
 * {@link java.util.concurrent.ForkJoinPool#commonPool()} while preserving deterministic insertion
 * order into Forge's {@code ImmutableMap.Builder}.
 */
public final class LayerDefinitionPerf {
    private LayerDefinitionPerf() {
    }

    public static <K, V> void evaluateAndInsert(
            Map<K, Supplier<V>> definitions,
            BiConsumer<K, V> sink) {
        if (definitions == null || definitions.isEmpty()) {
            return;
        }
        int size = definitions.size();
        if (size < 16) {
            for (Map.Entry<K, Supplier<V>> entry : definitions.entrySet()) {
                sink.accept(entry.getKey(), entry.getValue().get());
            }
            return;
        }
        List<Map.Entry<K, Supplier<V>>> entries = new ArrayList<>(definitions.entrySet());
        @SuppressWarnings("unchecked")
        V[] values = (V[]) new Object[size];
        boolean[] failed = new boolean[size];
        final ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        IntStream.range(0, size).parallel().forEach(i -> {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            if (previous != tccl) {
                thread.setContextClassLoader(tccl);
            }
            try {
                values[i] = entries.get(i).getValue().get();
            } catch (Throwable t) {
                failed[i] = true;
            } finally {
                if (thread.getContextClassLoader() != previous) {
                    thread.setContextClassLoader(previous);
                }
            }
        });
        for (int i = 0; i < size; i++) {
            Map.Entry<K, Supplier<V>> entry = entries.get(i);
            V val = failed[i] ? entry.getValue().get() : values[i];
            sink.accept(entry.getKey(), val);
        }
    }
}
