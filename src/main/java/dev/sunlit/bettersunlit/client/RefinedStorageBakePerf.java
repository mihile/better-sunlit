package dev.sunlit.bettersunlit.client;

import com.refinedmods.refinedstorage.render.BakedModelOverrideRegistry;
import dev.sunlit.fishvisuals.mixin.PerfBakedModelOverrideRegistryAccessor;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.event.ModelEvent;
import org.embeddedt.modernfix.forge.dynresources.ModelLocationBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Indexes Refined Storage's {@code BakedModelOverrideRegistry} by {@code (namespace, path)} so
 * {@code ClientSetup.onModelBake} avoids allocating a {@code ResourceLocation} for every model in
 * the registry.
 */
public final class RefinedStorageBakePerf {
    private static final Object CACHE_LOCK = new Object();
    private static volatile BakedModelOverrideRegistry cachedRegistry;
    private static volatile int cachedSize = -1;
    private static volatile Map<String, Map<String, BakedModelOverrideRegistry.BakedModelOverrideFactory>> cachedIndex;

    private RefinedStorageBakePerf() {
    }

    public static boolean tryApplyOverrides(
            ModelEvent.ModifyBakingResult event,
            BakedModelOverrideRegistry registry) {
        if (event == null || !(registry instanceof PerfBakedModelOverrideRegistryAccessor accessor)) {
            return false;
        }
        Map<ResourceLocation, BakedModelOverrideRegistry.BakedModelOverrideFactory> rawMap =
                accessor.sunlit$getRegistry();
        if (rawMap == null) {
            return false;
        }
        Map<String, Map<String, BakedModelOverrideRegistry.BakedModelOverrideFactory>> index =
                getOrBuildIndex(registry, rawMap);
        if (index.isEmpty()) {
            return true;
        }
        Map<ResourceLocation, BakedModel> models = event.getModels();
        DynamicLazyModelStore store = DynamicLazyModelHelper.resolveStore(models);
        if (store != null) {
            ModelLocationBuilder builder = new ModelLocationBuilder();
            Set<ResourceLocation> tmp = new ObjectLinkedOpenHashSet<>();
            Map<ResourceLocation, BakedModelOverrideRegistry.BakedModelOverrideFactory> unmatched = null;
            for (Map.Entry<ResourceLocation, BakedModelOverrideRegistry.BakedModelOverrideFactory> entry :
                    rawMap.entrySet()) {
                ResourceLocation baseId = entry.getKey();
                BakedModelOverrideRegistry.BakedModelOverrideFactory factory = entry.getValue();
                if (baseId == null || factory == null) {
                    continue;
                }
                Function<BakedModel, BakedModel> lazyFn = base -> base == null ? null : factory.create(base, models);
                boolean matched = false;
                Optional<Block> blockOpt = BuiltInRegistries.f_256975_.m_6612_(baseId);
                if (blockOpt.isPresent()) {
                    tmp.clear();
                    builder.generateForBlock(tmp, blockOpt.get(), baseId);
                    for (ResourceLocation loc : tmp) {
                        store.sunlit$addLazySwap(loc, lazyFn);
                    }
                    matched = true;
                }
                if (BuiltInRegistries.f_257033_.m_7804_(baseId)) {
                    store.sunlit$addLazySwap(new ModelResourceLocation(baseId, "inventory"), lazyFn);
                    matched = true;
                }
                if (!matched) {
                    if (unmatched == null) {
                        unmatched = new HashMap<>(4);
                    }
                    unmatched.put(baseId, factory);
                }
            }
            if (unmatched != null) {
                Map<String, Map<String, BakedModelOverrideRegistry.BakedModelOverrideFactory>> unmatchedIndex =
                        buildIndex(unmatched);
                applyScanOverrides(models, unmatchedIndex);
            }
            return true;
        }
        applyScanOverrides(models, index);
        return true;
    }

    private static void applyScanOverrides(
            Map<ResourceLocation, BakedModel> models,
            Map<String, Map<String, BakedModelOverrideRegistry.BakedModelOverrideFactory>> index) {
        for (ResourceLocation id : models.keySet()) {
            Map<String, BakedModelOverrideRegistry.BakedModelOverrideFactory> byPath = index.get(id.m_135827_());
            if (byPath == null) {
                continue;
            }
            BakedModelOverrideRegistry.BakedModelOverrideFactory factory = byPath.get(id.m_135815_());
            if (factory != null) {
                models.put(id, factory.create(models.get(id), models));
            }
        }
    }

    public static Map<String, Map<String, BakedModelOverrideRegistry.BakedModelOverrideFactory>> buildIndex(
            Map<ResourceLocation, BakedModelOverrideRegistry.BakedModelOverrideFactory> rawMap) {
        Map<String, Map<String, BakedModelOverrideRegistry.BakedModelOverrideFactory>> index = new HashMap<>(4);
        for (Map.Entry<ResourceLocation, BakedModelOverrideRegistry.BakedModelOverrideFactory> entry :
                rawMap.entrySet()) {
            ResourceLocation key = entry.getKey();
            index.computeIfAbsent(key.m_135827_(), k -> new HashMap<>())
                    .put(key.m_135815_(), entry.getValue());
        }
        return index;
    }

    private static Map<String, Map<String, BakedModelOverrideRegistry.BakedModelOverrideFactory>> getOrBuildIndex(
            BakedModelOverrideRegistry registry,
            Map<ResourceLocation, BakedModelOverrideRegistry.BakedModelOverrideFactory> rawMap) {
        int size = rawMap.size();
        if (cachedRegistry == registry && cachedSize == size && cachedIndex != null) {
            return cachedIndex;
        }
        synchronized (CACHE_LOCK) {
            if (cachedRegistry == registry && cachedSize == size && cachedIndex != null) {
                return cachedIndex;
            }
            Map<String, Map<String, BakedModelOverrideRegistry.BakedModelOverrideFactory>> built =
                    buildIndex(rawMap);
            cachedRegistry = registry;
            cachedSize = size;
            cachedIndex = built;
            return built;
        }
    }
}
