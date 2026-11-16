package dev.sunlit.bettersunlit.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
/**
 * Deduplicates canonical states before computing baked backpack shapes on the calling thread.
 * Model lookup can lazily load Forge classes and mutate the model bakery's caches.
 */
public final class BackpackShapePerf {
    private BackpackShapePerf() {
    }

    public static <S, V> Map<S, V> buildDeduplicatedShapes(
            List<S> allStates,
            Function<S, S> canonicalizer,
            Function<S, V> shapeComputer) {
        Map<S, Integer> canonicalIndex = new LinkedHashMap<>(256);
        List<S> uniqueCanonicals = new ArrayList<>(256);
        int total = allStates.size();
        int[] stateToCanonical = new int[total];
        for (int i = 0; i < total; i++) {
            S canonical = canonicalizer.apply(allStates.get(i));
            Integer idx = canonicalIndex.get(canonical);
            if (idx == null) {
                idx = uniqueCanonicals.size();
                canonicalIndex.put(canonical, idx);
                uniqueCanonicals.add(canonical);
            }
            stateToCanonical[i] = idx;
        }
        int uniqueCount = uniqueCanonicals.size();
        @SuppressWarnings("unchecked")
        V[] computed = (V[]) new Object[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            computed[i] = shapeComputer.apply(uniqueCanonicals.get(i));
        }
        Map<S, V> rebuilt = new HashMap<>(total * 2);
        for (int i = 0; i < total; i++) {
            rebuilt.put(allStates.get(i), computed[stateToCanonical[i]]);
        }
        return Map.copyOf(rebuilt);
    }
}
