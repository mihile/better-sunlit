package dev.sunlit.bettersunlit.perf;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deduplicates {@code WallBlock.makeShapes} output by indexing {@link VoxelShape} instances in
 * deterministic {@link StateDefinition#getPossibleStates()} order rather than hashing 324
 * {@code ImmutableMap<Property, Comparable>} state value maps per wall block.
 */
public final class WallShapePerf {
    private static final ConcurrentHashMap<ShapeKey, CachedShapes> CACHE = new ConcurrentHashMap<>(16);

    private WallShapePerf() {
    }

    public static Map<BlockState, VoxelShape> tryBuildFromCache(
            StateDefinition<Block, BlockState> stateDef,
            float f1, float f2, float f3, float f4, float f5, float f6) {
        if (stateDef == null) {
            return null;
        }
        ShapeKey key = ShapeKey.of(f1, f2, f3, f4, f5, f6);
        CachedShapes cached = CACHE.get(key);
        if (cached == null) {
            return null;
        }
        ImmutableList<BlockState> states = stateDef.m_61056_();
        VoxelShape[] shapes = cached.shapes;
        if (states == null || states.size() != shapes.length) {
            return null;
        }
        if (!samePropertiesInOrder(cached.properties, stateDef.m_61092_())) {
            return null;
        }
        int n = shapes.length;
        ImmutableMap.Builder<BlockState, VoxelShape> builder = ImmutableMap.builderWithExpectedSize(n);
        for (int i = 0; i < n; i++) {
            VoxelShape shape = shapes[i];
            if (shape == null) {
                return null;
            }
            builder.put(states.get(i), shape);
        }
        return builder.build();
    }

    public static void recordIfMissing(
            StateDefinition<Block, BlockState> stateDef,
            float f1, float f2, float f3, float f4, float f5, float f6,
            Map<BlockState, VoxelShape> computed) {
        if (stateDef == null || computed == null || computed.isEmpty()) {
            return;
        }
        ShapeKey key = ShapeKey.of(f1, f2, f3, f4, f5, f6);
        if (CACHE.containsKey(key)) {
            return;
        }
        ImmutableList<BlockState> states = stateDef.m_61056_();
        if (states == null || states.isEmpty()) {
            return;
        }
        int n = states.size();
        VoxelShape[] shapes = new VoxelShape[n];
        for (int i = 0; i < n; i++) {
            VoxelShape s = computed.get(states.get(i));
            if (s == null) {
                return;
            }
            shapes[i] = s;
        }
        List<Property<?>> props = List.copyOf(stateDef.m_61092_());
        CACHE.putIfAbsent(key, new CachedShapes(props, shapes));
    }

    private static boolean samePropertiesInOrder(List<Property<?>> expected, Collection<Property<?>> actual) {
        if (actual == null || expected.size() != actual.size()) {
            return false;
        }
        int i = 0;
        for (Iterator<Property<?>> it = actual.iterator(); it.hasNext(); i++) {
            if (!expected.get(i).equals(it.next())) {
                return false;
            }
        }
        return true;
    }

    private record ShapeKey(int a, int b, int c, int d, int e, int f) {
        static ShapeKey of(float f1, float f2, float f3, float f4, float f5, float f6) {
            return new ShapeKey(
                    Float.floatToIntBits(f1),
                    Float.floatToIntBits(f2),
                    Float.floatToIntBits(f3),
                    Float.floatToIntBits(f4),
                    Float.floatToIntBits(f5),
                    Float.floatToIntBits(f6));
        }
    }

    private record CachedShapes(List<Property<?>> properties, VoxelShape[] shapes) {
    }
}
