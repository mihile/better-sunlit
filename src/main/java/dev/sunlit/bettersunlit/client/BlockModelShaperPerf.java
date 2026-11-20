package dev.sunlit.bettersunlit.client;

import java.util.Arrays;
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
}
