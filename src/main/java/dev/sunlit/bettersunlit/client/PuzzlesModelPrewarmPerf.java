package dev.sunlit.bettersunlit.client;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.embeddedt.modernfix.forge.dynresources.ModelLocationBuilder;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

/**
 * Parallelizes {@code ModelLocationBuilder} key construction for {@code DiagonalBlocks} during early
 * resource reload and batches state model inserts into ModernFix's synchronized override map.
 */
public final class PuzzlesModelPrewarmPerf {
    private static volatile CompletableFuture<Map<Block, ResourceLocation[]>> prewarmFuture;
    private static volatile SoftReference<Map<Block, ResourceLocation[]>> cachedKeys = new SoftReference<>(null);

    private PuzzlesModelPrewarmPerf() {
    }

    public static void schedulePrewarm() {
        if (cachedKeys.get() != null || prewarmFuture != null) {
            return;
        }
        synchronized (PuzzlesModelPrewarmPerf.class) {
            if (cachedKeys.get() == null && prewarmFuture == null) {
                prewarmFuture = CompletableFuture.supplyAsync(
                        PuzzlesModelPrewarmPerf::computePrewarmKeys,
                        ForkJoinPool.commonPool());
            }
        }
    }

    private static Map<Block, ResourceLocation[]> computePrewarmKeys() {
        List<Block> blocks = PuzzlesModelPerf.collectDiagonalBlocks();
        if (blocks == null || blocks.isEmpty()) {
            return Map.of();
        }
        Map<Block, ResourceLocation[]> result = computeBlockStateKeysParallel(blocks);
        cachedKeys = new SoftReference<>(result);
        return result;
    }

    static Map<Block, ResourceLocation[]> computeBlockStateKeysParallel(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return Map.of();
        }
        int n = blocks.size();
        ResourceLocation[][] perBlock = new ResourceLocation[n][];
        if (n <= 8) {
            ModelLocationBuilder builder = new ModelLocationBuilder();
            for (int i = 0; i < n; i++) {
                perBlock[i] = buildKeysForBlock(builder, blocks.get(i));
            }
        } else {
            int workers = Math.min(n, Math.max(2, ForkJoinPool.commonPool().getParallelism()));
            int chunkSize = (n + workers - 1) / workers;
            List<CompletableFuture<Void>> tasks = new ArrayList<>(workers);
            for (int start = 0; start < n; start += chunkSize) {
                final int from = start;
                final int to = Math.min(n, start + chunkSize);
                tasks.add(CompletableFuture.runAsync(() -> {
                    ModelLocationBuilder builder = new ModelLocationBuilder();
                    for (int i = from; i < to; i++) {
                        perBlock[i] = buildKeysForBlock(builder, blocks.get(i));
                    }
                }, ForkJoinPool.commonPool()));
            }
            for (CompletableFuture<Void> task : tasks) {
                task.join();
            }
        }
        Map<Block, ResourceLocation[]> byBlock = new IdentityHashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            ResourceLocation[] keys = perBlock[i];
            if (keys != null) {
                byBlock.put(blocks.get(i), keys);
            }
        }
        return byBlock;
    }

    private static ResourceLocation[] buildKeysForBlock(ModelLocationBuilder builder, Block block) {
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.f_256975_.m_7981_(block);
        if (id == null) {
            return new ResourceLocation[0];
        }
        LinkedHashSet<ResourceLocation> set = new LinkedHashSet<>();
        builder.generateForBlock(set, block, id);
        return set.toArray(new ResourceLocation[0]);
    }

    static Map<Block, ResourceLocation[]> getPrewarmedKeys() {
        Map<Block, ResourceLocation[]> cached = cachedKeys.get();
        if (cached != null) {
            return cached;
        }
        CompletableFuture<Map<Block, ResourceLocation[]>> future = prewarmFuture;
        if (future != null && future.isDone() && !future.isCompletedExceptionally()) {
            cached = future.join();
            if (cached != null) {
                cachedKeys = new SoftReference<>(cached);
            }
            return cached;
        }
        return null;
    }

    public static void putAllStatesBatched(
            Map<ResourceLocation, BakedModel> models,
            Block diagonalBlock,
            List<BlockState> states,
            ResourceLocation id,
            BakedModel baked,
            boolean hasItem) {
        Map<Block, ResourceLocation[]> prewarmed = getPrewarmedKeys();
        ResourceLocation[] precomputedKeys = prewarmed != null && diagonalBlock != null ? prewarmed.get(diagonalBlock) : null;
        int capacity = (precomputedKeys != null ? precomputedKeys.length : states.size()) + (hasItem ? 1 : 0);
        if (capacity <= 0) {
            return;
        }
        Object2ObjectOpenHashMap<ResourceLocation, BakedModel> batch = new Object2ObjectOpenHashMap<>(capacity);
        if (precomputedKeys != null && precomputedKeys.length > 0) {
            for (ResourceLocation key : precomputedKeys) {
                batch.put(key, baked);
            }
        } else {
            for (int i = 0, len = states.size(); i < len; i++) {
                batch.put(BlockModelShaper.m_110895_(states.get(i)), baked);
            }
        }
        if (hasItem && id != null) {
            batch.put(new ModelResourceLocation(id, "inventory"), baked);
        }
        models.putAll(batch);
    }

    static void resetForTest() {
        synchronized (PuzzlesModelPrewarmPerf.class) {
            prewarmFuture = null;
            cachedKeys = new SoftReference<>(null);
        }
    }
}
