package dev.sunlit.bettersunlit.client;

import net.minecraft.world.level.block.state.properties.Property;

import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks which {@code BlockState} instances have had a model cached via ModernFix's
 * {@code BlockModelShaper.getBlockModel}, allowing {@code replaceModelMap} to clear only the
 * touched states (or skip entirely when zero states were cached) instead of scanning all 75,000+
 * registered block states on every model reload.
 */
public final class BlockModelShaperPerf {
    private static final int MAX_TRACKED = 2048;
    private static final Object[] EMPTY = new Object[0];
    private static final AtomicBoolean DIRTY = new AtomicBoolean(false);
    private static final Object LOCK = new Object();
    private static final Object[] TRACKED = new Object[MAX_TRACKED];
    private static int trackedCount;
    private static boolean overflowed;
    private static volatile Object lastTracked;

    private BlockModelShaperPerf() {
    }

    public static void markModelCached() {
        synchronized (LOCK) {
            DIRTY.lazySet(true);
            overflowed = true;
        }
    }

    public static void markCachedState(Object state) {
        if (!DIRTY.get()) {
            DIRTY.lazySet(true);
        }
        if (state == null) {
            markModelCached();
            return;
        }
        if (state == lastTracked || overflowed) {
            return;
        }
        synchronized (LOCK) {
            if (overflowed) {
                return;
            }
            for (int i = trackedCount - 1; i >= Math.max(0, trackedCount - 8); i--) {
                if (TRACKED[i] == state) {
                    lastTracked = state;
                    return;
                }
            }
            if (trackedCount < MAX_TRACKED) {
                TRACKED[trackedCount++] = state;
                lastTracked = state;
            } else {
                overflowed = true;
                Arrays.fill(TRACKED, 0, trackedCount, null);
                trackedCount = 0;
                lastTracked = null;
            }
        }
    }

    /**
     * Returns an empty array when nothing needs clearing, a non-empty array of states to clear
     * directly, or {@code null} when {@link #MAX_TRACKED} was exceeded and the caller must run the
     * full registry clear loop.
     */
    public static Object[] consumeStatesToClear() {
        synchronized (LOCK) {
            boolean wasDirty = DIRTY.getAndSet(false);
            lastTracked = null;
            if (!wasDirty) {
                trackedCount = 0;
                overflowed = false;
                return EMPTY;
            }
            if (overflowed) {
                overflowed = false;
                trackedCount = 0;
                return null;
            }
            int n = trackedCount;
            if (n == 0) {
                return EMPTY;
            }
            Object[] snap = Arrays.copyOf(TRACKED, n);
            Arrays.fill(TRACKED, 0, n, null);
            trackedCount = 0;
            return snap;
        }
    }

    public static boolean consumeNeedsClear() {
        synchronized (LOCK) {
            boolean wasDirty = DIRTY.getAndSet(false);
            if (trackedCount > 0) {
                Arrays.fill(TRACKED, 0, trackedCount, null);
                trackedCount = 0;
            }
            overflowed = false;
            lastTracked = null;
            return wasDirty;
        }
    }

    public static void resetForTest() {
        consumeNeedsClear();
    }

    private static final ThreadLocal<StringBuilder> TL_STATE_SB =
            ThreadLocal.withInitial(() -> new StringBuilder(64));
    private static final ConcurrentHashMap<Property<?>, IdentityHashMap<Object, String>> PROP_PAIR_CACHE =
            new ConcurrentHashMap<>(512);

    /**
     * Fast replacement for {@code BlockModelShaper.m_110887_(Map)} ({@code statePropertiesToString})
     * using cached {@code "prop=value"} pairs, zero-allocation fast paths for 0/1 properties,
     * and a reusable ThreadLocal {@link StringBuilder}.
     */
    public static String fastStatePropertiesToString(Map<Property<?>, Comparable<?>> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        int size = map.size();
        if (size == 1) {
            Map.Entry<Property<?>, Comparable<?>> entry = map.entrySet().iterator().next();
            return cachedPropPair(entry.getKey(), entry.getValue());
        }
        StringBuilder sb = TL_STATE_SB.get();
        sb.setLength(0);
        boolean first = true;
        for (Map.Entry<Property<?>, Comparable<?>> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            } else {
                first = false;
            }
            sb.append(cachedPropPair(entry.getKey(), entry.getValue()));
        }
        return sb.toString();
    }

    private static String cachedPropPair(Property<?> prop, Comparable<?> value) {
        IdentityHashMap<Object, String> byVal = PROP_PAIR_CACHE.get(prop);
        if (byVal == null) {
            Collection<? extends Comparable<?>> vals = prop.m_6908_();
            IdentityHashMap<Object, String> built = new IdentityHashMap<>(Math.max(4, vals.size() * 2));
            String prefix = prop.m_61708_() + "=";
            for (Comparable<?> v : vals) {
                built.put(v, prefix + getPropValueName(prop, v));
            }
            IdentityHashMap<Object, String> prev = PROP_PAIR_CACHE.putIfAbsent(prop, built);
            byVal = prev != null ? prev : built;
        }
        String cached = byVal.get(value);
        if (cached != null) {
            return cached;
        }
        return prop.m_61708_() + "=" + getPropValueName(prop, value);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String getPropValueName(Property<T> prop, Comparable<?> value) {
        return prop.m_6940_((T) value);
    }
}
