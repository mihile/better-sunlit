package dev.sunlit.bettersunlit.client;

import java.util.Arrays;

/** Stable descending numeric order, preserving the original merge sort's zero ties. */
public final class StableVertexRadixSort {
    private static final int RETAIN_LIMIT = 65536;
    private static final ThreadLocal<Workspace> LOCAL = ThreadLocal.withInitial(Workspace::new);
    private static final class Workspace {
        int[] scratch = new int[0];
        final int[] counts = new int[256];
    }
    private StableVertexRadixSort() {}

    /** Null leaves small or non-numeric inputs to the reviewed original implementation. */
    public static int[] sort(float[] keys) {
        int n = keys.length;
        if (n < 256) return null;
        for (float key : keys) if (Float.isNaN(key)) return null;
        Workspace workspace = LOCAL.get();
        int[] scratch;
        if (n > RETAIN_LIMIT) scratch = new int[n];
        else {
            if (workspace.scratch.length < n) workspace.scratch = new int[Integer.highestOneBit(n - 1) << 1];
            scratch = workspace.scratch;
        }
        int[] result = new int[n];
        for (int i = 0; i < n; i++) result[i] = i;
        int[] from = result, to = scratch, counts = workspace.counts;
        for (int shift = 0; shift < 32; shift += 8) {
            Arrays.fill(counts, 0);
            for (int i = 0; i < n; i++) counts[(key(keys[from[i]]) >>> shift) & 255]++;
            int offset = 0;
            for (int i = 0; i < 256; i++) { int size = counts[i]; counts[i] = offset; offset += size; }
            for (int i = 0; i < n; i++) { int index = from[i]; to[counts[(key(keys[index]) >>> shift) & 255]++] = index; }
            int[] swap = from; from = to; to = swap;
        }
        return result;
    }
    private static int key(float value) {
        int bits = value == 0.0f ? 0 : Float.floatToRawIntBits(value);
        return ~(bits ^ ((bits >> 31) | Integer.MIN_VALUE));
    }
}
