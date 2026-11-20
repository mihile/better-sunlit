package dev.sunlit.bettersunlit.perf;

import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Deduplicates immutable 16-element {@link VoxelShape} arrays built by
 * {@code CrossCollisionBlock.makeShapes} across fences, panes, bars, and walls with identical
 * geometric parameters.
 */
public final class CrossCollisionShapePerf {
    private record Key(int a, int b, int c, int d, int e) {
        static Key of(float f1, float f2, float f3, float f4, float f5) {
            return new Key(
                    Float.floatToIntBits(f1),
                    Float.floatToIntBits(f2),
                    Float.floatToIntBits(f3),
                    Float.floatToIntBits(f4),
                    Float.floatToIntBits(f5));
        }
    }

    private static final ConcurrentHashMap<Key, VoxelShape[]> CACHE = new ConcurrentHashMap<>(32);

    private CrossCollisionShapePerf() {
    }

    public static VoxelShape[] get(float f1, float f2, float f3, float f4, float f5) {
        VoxelShape[] hit = CACHE.get(Key.of(f1, f2, f3, f4, f5));
        return hit != null ? hit.clone() : null;
    }

    public static void put(float f1, float f2, float f3, float f4, float f5, VoxelShape[] shapes) {
        if (shapes == null || shapes.length == 0 || CACHE.size() >= 256) {
            return;
        }
        CACHE.putIfAbsent(Key.of(f1, f2, f3, f4, f5), shapes.clone());
    }
}
