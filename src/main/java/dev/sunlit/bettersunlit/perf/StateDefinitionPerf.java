package dev.sunlit.bettersunlit.perf;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Iterative cartesian product replacement for {@code StateDefinition.<init>} that avoids
 * nested Java {@code Stream.flatMap}, intermediate {@code ArrayList} copies, {@code Pair} boxing,
 * and un-sized {@code ImmutableMap.toImmutableMap} collector overhead.
 */
public final class StateDefinitionPerf {
    @FunctionalInterface
    private interface LayoutFn {
        int[] compute(Object[] props);
    }

    @FunctionalInterface
    private interface BuildStateMapFn {
        ImmutableMap<Property<?>, Comparable<?>> build(Object[] keys, Object[] vals, int[] layout);
    }

    @FunctionalInterface
    private interface ClearCacheFn {
        void clear(Object fastMap);
    }

    private static volatile Thread SINGLE_STATE_THREAD;
    private static final List<Runnable> POST_CONSTRUCT_HOOKS = new ArrayList<>(4);
    private static volatile boolean POST_CONSTRUCT_FIRED;
    private static final ThreadLocal<PropertyMapReuse> REGISTRY_MAPS = new ThreadLocal<>();
    private static final LayoutFn PRECOMPUTE_LAYOUT_FN;
    private static final BuildStateMapFn BUILD_STATE_MAP_FN;
    private static final ThreadLocal<?> LAST_FAST_STATE_MAP_TL;
    private static final ClearCacheFn CLEAR_FAST_MAP_CACHE_FN;

    static {
        LayoutFn layoutFn = null;
        BuildStateMapFn buildFn = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle layoutMh = lookup.findStatic(
                    ImmutableMap.class,
                    "sunlitPrecomputeLayout",
                    MethodType.methodType(int[].class, Object[].class));
            layoutFn = (LayoutFn) LambdaMetafactory.metafactory(
                    lookup,
                    "compute",
                    MethodType.methodType(LayoutFn.class),
                    MethodType.methodType(int[].class, Object[].class),
                    layoutMh,
                    layoutMh.type()).getTarget().invokeExact();

            MethodHandle buildMh = lookup.findStatic(
                    ImmutableMap.class,
                    "sunlitBuildStateMap",
                    MethodType.methodType(ImmutableMap.class, Object[].class, Object[].class, int[].class));
            buildFn = (BuildStateMapFn) LambdaMetafactory.metafactory(
                    lookup,
                    "build",
                    MethodType.methodType(BuildStateMapFn.class),
                    MethodType.methodType(ImmutableMap.class, Object[].class, Object[].class, int[].class),
                    buildMh,
                    buildMh.type()).getTarget().invokeExact();
        } catch (Throwable ignored) {
        }
        PRECOMPUTE_LAYOUT_FN = layoutFn;
        BUILD_STATE_MAP_FN = buildFn;

        ThreadLocal<?> lastMapTl = null;
        ClearCacheFn clearFn = null;
        try {
            ClassLoader cl = StateDefinitionPerf.class.getClassLoader();
            Class<?> holderImplCls = Class.forName("malte0811.ferritecore.impl.StateHolderImpl", false, cl);
            Object tlObj = holderImplCls.getField("LAST_FAST_STATE_MAP").get(null);
            if (tlObj instanceof ThreadLocal<?> tl) {
                Class<?> fastMapCls = Class.forName("malte0811.ferritecore.fastmap.FastMap", false, cl);
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MethodHandle clearMh = lookup.findVirtual(fastMapCls, "sunlitClearIndexCache", MethodType.methodType(void.class));
                clearFn = (ClearCacheFn) LambdaMetafactory.metafactory(
                        lookup,
                        "clear",
                        MethodType.methodType(ClearCacheFn.class),
                        MethodType.methodType(void.class, Object.class),
                        clearMh,
                        MethodType.methodType(void.class, fastMapCls)).getTarget().invokeExact();
                lastMapTl = tl;
            }
        } catch (Throwable ignored) {
        }
        LAST_FAST_STATE_MAP_TL = lastMapTl;
        CLEAR_FAST_MAP_CACHE_FN = clearFn;
    }

    public static void finishStateDefinition() {
        ThreadLocal<?> tl = LAST_FAST_STATE_MAP_TL;
        ClearCacheFn fn = CLEAR_FAST_MAP_CACHE_FN;
        if (tl == null || fn == null) {
            return;
        }
        Object fastMap = tl.get();
        if (fastMap != null) {
            try {
                fn.clear(fastMap);
            } catch (Throwable ignored) {
            }
        }
    }

    private StateDefinitionPerf() {
    }

    public static void addOnFirstPopulate(Runnable hook) {
        if (hook == null) {
            return;
        }
        boolean runNow = false;
        synchronized (POST_CONSTRUCT_HOOKS) {
            if (POST_CONSTRUCT_FIRED) {
                runNow = true;
            } else {
                POST_CONSTRUCT_HOOKS.add(hook);
            }
        }
        if (runNow) {
            try {
                hook.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void runPostConstructHooks() {
        if (POST_CONSTRUCT_FIRED) {
            return;
        }
        List<Runnable> toRun = null;
        synchronized (POST_CONSTRUCT_HOOKS) {
            if (!POST_CONSTRUCT_FIRED) {
                POST_CONSTRUCT_FIRED = true;
                if (!POST_CONSTRUCT_HOOKS.isEmpty()) {
                    toRun = new ArrayList<>(POST_CONSTRUCT_HOOKS);
                    POST_CONSTRUCT_HOOKS.clear();
                }
            }
        }
        if (toRun != null) {
            for (int i = 0; i < toRun.size(); i++) {
                try {
                    toRun.get(i).run();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static void setSingleStateThread(Thread thread) {
        SINGLE_STATE_THREAD = thread;
    }

    public static <S> ImmutableList<S> getPossibleStates(ImmutableList<S> allStates) {
        if (SINGLE_STATE_THREAD == Thread.currentThread() && allStates != null && allStates.size() > 1) {
            return ImmutableList.of(allStates.get(0));
        }
        return allStates;
    }

    public static void beginRegistryMapReuse() {
        REGISTRY_MAPS.set(new PropertyMapReuse());
    }

    public static void endRegistryMapReuse() {
        REGISTRY_MAPS.remove();
    }

    private static final class PropertyMapReuse {
        private PropertyMaps previous;
    }

    private record PropertyMaps(Property<?>[] properties, ImmutableMap<Property<?>, Comparable<?>>[] maps) {
        boolean matches(Property<?>[] other) {
            if (properties.length != other.length) return false;
            for (int i = 0; i < properties.length; i++) if (properties[i] != other[i]) return false;
            return true;
        }
    }

    private static boolean hasStablePropertyValues(Property<?>[] properties, long count) {
        if (count < 2 || count > 512) return false;
        for (Property<?> property : properties) {
            Class<?> type = property.getClass();
            if (type != BooleanProperty.class && type != IntegerProperty.class
                    && type != EnumProperty.class && type != DirectionProperty.class) return false;
        }
        return true;
    }

    public static <O, S extends StateHolder<O, S>> void populateAllStates(
            ImmutableSortedMap<String, Property<?>> propertiesByName,
            StateDefinition.Factory<O, S> factory,
            O owner,
            MapCodec<S> codec,
            Map<Map<Property<?>, Comparable<?>>, S> byValues,
            List<S> statesOut) {
        int propCount = propertiesByName.size();
        if (propCount == 0) {
            ImmutableMap<Property<?>, Comparable<?>> empty = ImmutableMap.of();
            S state = factory.m_61106_(owner, empty, codec);
            byValues.put(empty, state);
            statesOut.add(state);
            return;
        }

        Property<?>[] props = new Property<?>[propCount];
        Comparable<?>[][] values = new Comparable<?>[propCount][];
        int pIdx = 0;
        long totalStatesLong = 1L;
        for (Property<?> prop : propertiesByName.values()) {
            props[pIdx] = prop;
            Collection<? extends Comparable<?>> possible = prop.m_6908_();
            values[pIdx] = possible.toArray(new Comparable<?>[possible.size()]);
            if (values[pIdx].length == 0) {
                return;
            }
            totalStatesLong *= values[pIdx].length;
            pIdx++;
        }
        if (statesOut instanceof ArrayList<?> statesArray
                && totalStatesLong > 0L
                && totalStatesLong <= Integer.MAX_VALUE - statesArray.size()) {
            statesArray.ensureCapacity(statesArray.size() + (int) totalStatesLong);
        }

        var sharing = REGISTRY_MAPS.get();
        PropertyMaps previous = sharing == null ? null : sharing.previous;
        if (previous != null && previous.matches(props)) {
            for (var propMap : previous.maps()) {
                S state = factory.m_61106_(owner, propMap, codec);
                byValues.put(propMap, state);
                statesOut.add(state);
            }
            return;
        }
        @SuppressWarnings("unchecked")
        ImmutableMap<Property<?>, Comparable<?>>[] nextMaps = sharing != null && hasStablePropertyValues(props, totalStatesLong)
                ? (ImmutableMap<Property<?>, Comparable<?>>[]) new ImmutableMap<?, ?>[(int) totalStatesLong]
                : null;
        int mapIndex = 0;

        Object[] stateVals = (propCount >= 2 && BUILD_STATE_MAP_FN != null)
                ? new Object[propCount]
                : null;

        int[] indices = new int[propCount];
        while (true) {
            ImmutableMap<Property<?>, Comparable<?>> propMap;
            if (stateVals != null) {
                for (int i = 0; i < propCount; i++) {
                    stateVals[i] = values[i][indices[i]];
                }
                propMap = BUILD_STATE_MAP_FN.build((Object[]) props, stateVals, null);
            } else {
                propMap = null;
            }
            if (propMap == null) {
                propMap = switch (propCount) {
                case 1 -> ImmutableMap.of(props[0], values[0][indices[0]]);
                case 2 -> ImmutableMap.of(
                        props[0], values[0][indices[0]],
                        props[1], values[1][indices[1]]);
                case 3 -> ImmutableMap.of(
                        props[0], values[0][indices[0]],
                        props[1], values[1][indices[1]],
                        props[2], values[2][indices[2]]);
                case 4 -> ImmutableMap.of(
                        props[0], values[0][indices[0]],
                        props[1], values[1][indices[1]],
                        props[2], values[2][indices[2]],
                        props[3], values[3][indices[3]]);
                case 5 -> ImmutableMap.of(
                        props[0], values[0][indices[0]],
                        props[1], values[1][indices[1]],
                        props[2], values[2][indices[2]],
                        props[3], values[3][indices[3]],
                        props[4], values[4][indices[4]]);
                case 6 -> ImmutableMap.of(
                        props[0], values[0][indices[0]],
                        props[1], values[1][indices[1]],
                        props[2], values[2][indices[2]],
                        props[3], values[3][indices[3]],
                        props[4], values[4][indices[4]],
                        props[5], values[5][indices[5]]);
                case 7 -> ImmutableMap.of(
                        props[0], values[0][indices[0]],
                        props[1], values[1][indices[1]],
                        props[2], values[2][indices[2]],
                        props[3], values[3][indices[3]],
                        props[4], values[4][indices[4]],
                        props[5], values[5][indices[5]],
                        props[6], values[6][indices[6]]);
                case 8 -> ImmutableMap.of(
                        props[0], values[0][indices[0]],
                        props[1], values[1][indices[1]],
                        props[2], values[2][indices[2]],
                        props[3], values[3][indices[3]],
                        props[4], values[4][indices[4]],
                        props[5], values[5][indices[5]],
                        props[6], values[6][indices[6]],
                        props[7], values[7][indices[7]]);
                case 9 -> ImmutableMap.of(
                        props[0], values[0][indices[0]],
                        props[1], values[1][indices[1]],
                        props[2], values[2][indices[2]],
                        props[3], values[3][indices[3]],
                        props[4], values[4][indices[4]],
                        props[5], values[5][indices[5]],
                        props[6], values[6][indices[6]],
                        props[7], values[7][indices[7]],
                        props[8], values[8][indices[8]]);
                case 10 -> ImmutableMap.of(
                        props[0], values[0][indices[0]],
                        props[1], values[1][indices[1]],
                        props[2], values[2][indices[2]],
                        props[3], values[3][indices[3]],
                        props[4], values[4][indices[4]],
                        props[5], values[5][indices[5]],
                        props[6], values[6][indices[6]],
                        props[7], values[7][indices[7]],
                        props[8], values[8][indices[8]],
                        props[9], values[9][indices[9]]);
                default -> {
                    ImmutableMap.Builder<Property<?>, Comparable<?>> builder =
                            ImmutableMap.builderWithExpectedSize(propCount);
                    for (int i = 0; i < propCount; i++) {
                        builder.put(props[i], values[i][indices[i]]);
                    }
                    yield builder.build();
                }
            };
            }
            if (nextMaps != null) nextMaps[mapIndex++] = propMap;
            S state = factory.m_61106_(owner, propMap, codec);
            byValues.put(propMap, state);
            statesOut.add(state);

            int carry = propCount - 1;
            while (carry >= 0) {
                if (++indices[carry] < values[carry].length) {
                    break;
                }
                indices[carry] = 0;
                carry--;
            }
            if (carry < 0) {
                break;
            }
        }
        if (nextMaps != null) sharing.previous = new PropertyMaps(props, nextMaps);
    }
}
