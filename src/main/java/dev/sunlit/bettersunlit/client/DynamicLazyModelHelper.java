package dev.sunlit.bettersunlit.client;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;

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
}
