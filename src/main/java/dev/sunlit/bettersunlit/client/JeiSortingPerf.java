package dev.sunlit.bettersunlit.client;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * O(1) index-order comparators replacing repeated {@link List#indexOf(Object)} linear scans in JEI {@code SortingConfig}.
 */
public final class JeiSortingPerf {
    private static final int LINEAR_THRESHOLD = 16;

    private JeiSortingPerf() {}

    public static <T, V> Comparator<V> comparator(List<T> sorted, Function<V, T> mapper) {
        if (sorted.size() <= LINEAR_THRESHOLD) {
            return Comparator.comparingInt(v -> indexOfSort(sorted.indexOf(mapper.apply(v))));
        }
        Object2IntOpenHashMap<Object> indexMap = buildFirstIndexMap(sorted);
        return Comparator.comparingInt(v -> indexMap.getInt(mapper.apply(v)));
    }

    @SuppressWarnings("unchecked")
    public static <T> Comparator<T> fileOrderThenDefault(Comparator<T> defaultOrder, List<T> sortedFromFile) {
        Comparator<T> byFile;
        if (sortedFromFile.size() <= LINEAR_THRESHOLD) {
            byFile = Comparator.comparingInt(v -> indexOfSort(sortedFromFile.indexOf(v)));
        } else {
            Object2IntOpenHashMap<Object> indexMap = buildFirstIndexMap(sortedFromFile);
            byFile = Comparator.comparingInt(indexMap::getInt);
        }
        return byFile.thenComparing(defaultOrder);
    }

    public static int indexOfSort(int index) {
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static <T> Object2IntOpenHashMap<Object> buildFirstIndexMap(List<T> list) {
        int size = list.size();
        Object2IntOpenHashMap<Object> map = new Object2IntOpenHashMap<>(size);
        map.defaultReturnValue(Integer.MAX_VALUE);
        // Reverse traversal ensures the earliest occurrence wins if duplicates are present.
        for (int i = size - 1; i >= 0; i--) {
            map.put(list.get(i), i);
        }
        return map;
    }
}
