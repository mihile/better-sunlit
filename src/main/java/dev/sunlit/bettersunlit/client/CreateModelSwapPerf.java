package dev.sunlit.bettersunlit.client;

import com.simibubi.create.foundation.block.render.CustomBlockModels;
import com.simibubi.create.foundation.item.render.CustomItemModels;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.CustomRenderedItems;
import com.simibubi.create.foundation.model.ModelSwapper;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.event.ModelEvent;
import org.embeddedt.modernfix.forge.dynresources.ModelLocationBuilder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

/**
 * Parallelizes Create's blockstate {@code ModelLocationBuilder} expansion across
 * {@code ForkJoinPool} and batches {@code models.putAll} updates to avoid repeated
 * {@code Collections.synchronizedMap} locking inside ModernFix's model registry.
 */
public final class CreateModelSwapPerf {
    private static final int BLOCK_BATCH_SIZE = 16;

    private CreateModelSwapPerf() {
    }

    public static void fastOnModelBake(
            ModelEvent.ModifyBakingResult event,
            CustomBlockModels customBlockModels,
            CustomItemModels customItemModels) {
        Map<ResourceLocation, BakedModel> models = event.getModels();
        List<SwapEntry<Block>> blockSwaps = extractSwapEntries(customBlockModels, Block.class);
        List<SwapEntry<Item>> itemSwaps = extractSwapEntries(customItemModels, Item.class);

        List<Item> customRendered = new ArrayList<>();
        CustomRenderedItems.forEach(customRendered::add);

        ResourceLocation[][] blockLocations = buildAllBlockLocationsParallel(blockSwaps);
        DynamicLazyModelStore store = DynamicLazyModelHelper.resolveStore(models);
        if (store != null) {
            for (int i = 0; i < blockSwaps.size(); i++) {
                ResourceLocation[] locs = blockLocations[i];
                if (locs == null || locs.length == 0) {
                    continue;
                }
                Function<BakedModel, ? extends BakedModel> fn = blockSwaps.get(i).fn;
                for (ResourceLocation loc : locs) {
                    store.sunlit$addLazySwap(loc, fn);
                }
            }
            for (SwapEntry<Item> swap : itemSwaps) {
                store.sunlit$addLazySwap(ModelSwapper.getItemModelLocation(swap.key), swap.fn);
            }
            for (Item item : customRendered) {
                store.sunlit$addLazySwap(ModelSwapper.getItemModelLocation(item), CustomRenderedItemModel::new);
            }
            return;
        }
        int estimatedEntries = itemSwaps.size() + customRendered.size();
        for (ResourceLocation[] locs : blockLocations) {
            if (locs != null) {
                estimatedEntries += locs.length;
            }
        }

        Map<ResourceLocation, BakedModel> swapped = new Object2ObjectOpenHashMap<>(Math.max(16, estimatedEntries));
        for (int i = 0; i < blockSwaps.size(); i++) {
            ResourceLocation[] locs = blockLocations[i];
            if (locs == null || locs.length == 0) {
                continue;
            }
            Function<BakedModel, ? extends BakedModel> fn = blockSwaps.get(i).fn;
            for (ResourceLocation loc : locs) {
                applySwap(models, swapped, loc, fn);
            }
        }
        for (SwapEntry<Item> swap : itemSwaps) {
            applySwap(models, swapped, ModelSwapper.getItemModelLocation(swap.key), swap.fn);
        }
        for (Item item : customRendered) {
            applySwap(models, swapped, ModelSwapper.getItemModelLocation(item), CustomRenderedItemModel::new);
        }
        if (!swapped.isEmpty()) {
            models.putAll(swapped);
        }
    }

    @SuppressWarnings("unchecked")
    private static <K> List<SwapEntry<K>> extractSwapEntries(Object modelRegistry, Class<K> keyType) {
        if (modelRegistry == null) {
            return List.of();
        }
        try {
            Method loader = modelRegistry.getClass().getDeclaredMethod("loadEntriesIfMissing");
            loader.setAccessible(true);
            loader.invoke(modelRegistry);
            Field field = modelRegistry.getClass().getDeclaredField("finalModelFuncs");
            field.setAccessible(true);
            Map<?, ?> raw = (Map<?, ?>) field.get(modelRegistry);
            List<SwapEntry<K>> out = new ArrayList<>(raw.size());
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (keyType.isInstance(entry.getKey()) && entry.getValue() instanceof Function<?, ?> fn) {
                    out.add(new SwapEntry<>(keyType.cast(entry.getKey()), (Function<BakedModel, ? extends BakedModel>) fn));
                }
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed reading Create custom model registry " + modelRegistry.getClass().getName(), e);
        }
    }

    private static void applySwap(
            Map<ResourceLocation, BakedModel> models,
            Map<ResourceLocation, BakedModel> swapped,
            ResourceLocation loc,
            Function<BakedModel, ? extends BakedModel> fn) {
        BakedModel current = swapped.get(loc);
        if (current == null) {
            current = models.get(loc);
        }
        swapped.put(loc, fn.apply(current));
    }

    private static ResourceLocation[][] buildAllBlockLocationsParallel(List<SwapEntry<Block>> swaps) {
        int count = swaps.size();
        ResourceLocation[][] result = new ResourceLocation[count][];
        if (count == 0) {
            return result;
        }
        if (count < 4) {
            ModelLocationBuilder builder = new ModelLocationBuilder();
            Set<ResourceLocation> tmp = new ObjectLinkedOpenHashSet<>();
            for (int i = 0; i < count; i++) {
                result[i] = buildBlockLocations(builder, tmp, swaps.get(i).key);
            }
            return result;
        }
        ForkJoinPool pool = ForkJoinPool.commonPool();
        int batches = (count + BLOCK_BATCH_SIZE - 1) / BLOCK_BATCH_SIZE;
        List<CompletableFuture<Void>> futures = new ArrayList<>(batches);
        for (int start = 0; start < count; start += BLOCK_BATCH_SIZE) {
            final int from = start;
            final int to = Math.min(count, from + BLOCK_BATCH_SIZE);
            futures.add(CompletableFuture.runAsync(() -> {
                ModelLocationBuilder builder = new ModelLocationBuilder();
                Set<ResourceLocation> tmp = new ObjectLinkedOpenHashSet<>();
                for (int i = from; i < to; i++) {
                    result[i] = buildBlockLocations(builder, tmp, swaps.get(i).key);
                }
            }, pool));
        }
        for (CompletableFuture<Void> future : futures) {
            future.join();
        }
        return result;
    }

    private static ResourceLocation[] buildBlockLocations(
            ModelLocationBuilder builder, Set<ResourceLocation> tmp, Block block) {
        tmp.clear();
        ResourceLocation key = BuiltInRegistries.f_256975_.m_7981_(block);
        builder.generateForBlock(tmp, block, key);
        return tmp.toArray(new ResourceLocation[0]);
    }

    private static final class SwapEntry<K> {
        final K key;
        final Function<BakedModel, ? extends BakedModel> fn;

        SwapEntry(K key, Function<BakedModel, ? extends BakedModel> fn) {
            this.key = key;
            this.fn = fn;
        }
    }
}
