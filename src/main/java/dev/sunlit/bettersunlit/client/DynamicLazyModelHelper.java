package dev.sunlit.bettersunlit.client;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Coordinates lazy {@code BakedModel} swap registration and resolution on ModernFix's
 * {@code DynamicBakedModelProvider}.
 */
public final class DynamicLazyModelHelper {
    private static volatile DynamicLazyModelStore lastStore;

    private DynamicLazyModelHelper() {
    }

    /**
     * Returns a lock-free {@link ConcurrentHashMap}-backed map that permits {@code null} values
     * (matching {@code Object2ObjectOpenHashMap} semantics in ModernFix's
     * {@code DynamicBakedModelProvider.permanentOverrides}) so concurrent chunk builder threads
     * never contend on a global {@code Collections.synchronizedMap} monitor during post-join
     * chunk meshing.
     */
    public static Map<ResourceLocation, BakedModel> newConcurrentOverridesMap(
            Map<ResourceLocation, BakedModel> existing) {
        NullSafeConcurrentOverridesMap map = new NullSafeConcurrentOverridesMap();
        if (existing != null && !existing.isEmpty()) {
            map.putAll(existing);
        }
        return map;
    }

    public static void setLastStore(DynamicLazyModelStore store) {
        lastStore = store;
    }

    public static DynamicLazyModelStore resolveStore(Map<ResourceLocation, BakedModel> models) {
        if (models instanceof DynamicLazyModelStore store) {
            return store;
        }
        if (models != null
                && "org.embeddedt.modernfix.forge.dynresources.ModelBakeEventHelper$EmulatedModelRegistry"
                        .equals(models.getClass().getName())) {
            return lastStore;
        }
        return null;
    }

    public static void addLazySwap(
            ConcurrentHashMap<ResourceLocation, LazySlot> lazySwaps,
            Map<ResourceLocation, BakedModel> permanentOverrides,
            ResourceLocation loc,
            Function<BakedModel, ? extends BakedModel> fn) {
        if (loc == null || fn == null) {
            return;
        }
        BakedModel already = permanentOverrides.get(loc);
        if (already != null) {
            BakedModel swapped = fn.apply(already);
            if (swapped != null) {
                permanentOverrides.put(loc, swapped);
            }
            return;
        }
        lazySwaps.compute(loc, (k, existing) -> {
            if (existing == null || existing.resolved) {
                return new LazySlot(fn);
            }
            return existing.compose(fn);
        });
    }

    @SuppressWarnings("unchecked")
    public static BakedModel resolveLazySwap(
            Object providerObj,
            ConcurrentHashMap<ResourceLocation, LazySlot> lazySwaps,
            Map<ResourceLocation, BakedModel> permanentOverrides,
            ResourceLocation loc,
            Set<ResourceLocation> active) {
        LazySlot slot = lazySwaps.get(loc);
        if (slot == null) {
            return null;
        }
        synchronized (slot) {
            if (!slot.resolved) {
                BakedModel base = permanentOverrides.get(loc);
                if (base == null) {
                    active.add(loc);
                    try {
                        base = ((Map<ResourceLocation, BakedModel>) providerObj).get(loc);
                    } finally {
                        active.remove(loc);
                    }
                }
                BakedModel swapped = slot.fn.apply(base);
                if (swapped != null) {
                    permanentOverrides.put(loc, swapped);
                }
                slot.value = swapped;
                slot.resolved = true;
                lazySwaps.remove(loc, slot);
            }
            return slot.value;
        }
    }

    public static final class LazySlot {
        volatile Function<BakedModel, ? extends BakedModel> fn;
        volatile boolean resolved;
        BakedModel value;

        LazySlot(Function<BakedModel, ? extends BakedModel> fn) {
            this.fn = fn;
        }

        synchronized LazySlot compose(Function<BakedModel, ? extends BakedModel> next) {
            if (this.resolved) {
                return new LazySlot(next);
            }
            final Function<BakedModel, ? extends BakedModel> prev = this.fn;
            this.fn = base -> next.apply(prev.apply(base));
            return this;
        }
    }

    /**
     * Lock-free {@link ConcurrentHashMap} wrapper that accepts {@code null} values by encoding them
     * with a private sentinel object, preserving exact {@code getOrDefault(k, SENTINEL)} semantics
     * for {@code DynamicBakedModelProvider.permanentOverrides}.
     */
    private static final class NullSafeConcurrentOverridesMap extends AbstractMap<ResourceLocation, BakedModel> {
        private static final Object NULL_VALUE = new Object();
        private final ConcurrentHashMap<ResourceLocation, Object> delegate = new ConcurrentHashMap<>(256);

        private static BakedModel unwrap(Object raw) {
            return raw == NULL_VALUE ? null : (BakedModel) raw;
        }

        private static Object wrap(BakedModel value) {
            return value == null ? NULL_VALUE : value;
        }

        @Override
        public BakedModel get(Object key) {
            if (key == null) {
                return null;
            }
            return unwrap(delegate.get(key));
        }

        @Override
        public BakedModel getOrDefault(Object key, BakedModel defaultValue) {
            if (key == null) {
                return defaultValue;
            }
            Object raw = delegate.get(key);
            if (raw == null) {
                return defaultValue;
            }
            return raw == NULL_VALUE ? null : (BakedModel) raw;
        }

        @Override
        public boolean containsKey(Object key) {
            return key != null && delegate.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return delegate.containsValue(value == null ? NULL_VALUE : value);
        }

        @Override
        public BakedModel put(ResourceLocation key, BakedModel value) {
            if (key == null) {
                return null;
            }
            return unwrap(delegate.put(key, wrap(value)));
        }

        @Override
        public void putAll(Map<? extends ResourceLocation, ? extends BakedModel> m) {
            if (m == null || m.isEmpty()) {
                return;
            }
            for (Entry<? extends ResourceLocation, ? extends BakedModel> e : m.entrySet()) {
                ResourceLocation k = e.getKey();
                if (k != null) {
                    delegate.put(k, wrap(e.getValue()));
                }
            }
        }

        @Override
        public BakedModel remove(Object key) {
            if (key == null) {
                return null;
            }
            return unwrap(delegate.remove(key));
        }

        @Override
        public BakedModel replace(ResourceLocation key, BakedModel value) {
            if (key == null) {
                return null;
            }
            return unwrap(delegate.replace(key, wrap(value)));
        }

        @Override
        public boolean replace(ResourceLocation key, BakedModel oldValue, BakedModel newValue) {
            if (key == null) {
                return false;
            }
            return delegate.replace(key, wrap(oldValue), wrap(newValue));
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }

        @Override
        public Set<ResourceLocation> keySet() {
            return delegate.keySet();
        }

        @Override
        public Set<Entry<ResourceLocation, BakedModel>> entrySet() {
            Map<ResourceLocation, BakedModel> snapshot = new java.util.HashMap<>(delegate.size());
            for (Entry<ResourceLocation, Object> e : delegate.entrySet()) {
                snapshot.put(e.getKey(), unwrap(e.getValue()));
            }
            return Collections.unmodifiableSet(snapshot.entrySet());
        }

        @Override
        public Collection<BakedModel> values() {
            java.util.ArrayList<BakedModel> out = new java.util.ArrayList<>(delegate.size());
            for (Object v : delegate.values()) {
                out.add(unwrap(v));
            }
            return Collections.unmodifiableList(out);
        }
    }
}
