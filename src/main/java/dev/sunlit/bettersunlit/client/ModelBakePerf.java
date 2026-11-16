package dev.sunlit.bettersunlit.client;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.ModList;
import org.embeddedt.modernfix.forge.dynresources.ModelLocationBuilder;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

/**
 * Precomputes the static registry portion of ModernFix's {@code ModelBakeEventHelper} on a
 * background worker as soon as {@code ModelBakery.<init>} begins, then clones the precomputed
 * fastutil sets when {@code ModelBakeEventHelper.<init>} executes.
 */
public final class ModelBakePerf {
    private static volatile CompletableFuture<PrewarmData> prewarmFuture;
    private static volatile SoftReference<PrewarmData> cachedData = new SoftReference<>(null);
    private static final ThreadLocal<PrewarmData> ACTIVE_APPLY = new ThreadLocal<>();

    private ModelBakePerf() {
    }

    public static void schedulePrewarm() {
        if (cachedData.get() != null) {
            return;
        }
        if (prewarmFuture != null) {
            return;
        }
        synchronized (ModelBakePerf.class) {
            if (cachedData.get() == null && prewarmFuture == null) {
                prewarmFuture = CompletableFuture.supplyAsync(ModelBakePerf::computeStaticData, ForkJoinPool.commonPool());
            }
        }
    }

    public static PrewarmData awaitPrewarm() {
        PrewarmData cached = cachedData.get();
        if (cached != null) {
            return cached;
        }
        CompletableFuture<PrewarmData> future = prewarmFuture;
        if (future == null) {
            return null;
        }
        try {
            PrewarmData data = future.join();
            if (data != null) {
                cachedData = new SoftReference<>(data);
            }
            return data;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static boolean beginIfReady() {
        if (!RenderSystem.isOnRenderThread()) {
            PrewarmData data = awaitPrewarm();
            if (data != null) {
                ACTIVE_APPLY.set(data);
                return true;
            }
            return false;
        }
        PrewarmData data = awaitPrewarm();
        if (data != null) {
            ACTIVE_APPLY.set(data);
            return true;
        }
        return false;
    }

    public static PrewarmData getActivePrewarm() {
        return ACTIVE_APPLY.get();
    }

    public static void finishApply() {
        ACTIVE_APPLY.remove();
    }

    private static final int PARALLEL_BLOCK_CHUNK = 256;

    public static PrewarmData computeStaticData() {
        int stateCount = 0;
        for (Block block : BuiltInRegistries.f_256975_) {
            stateCount += block.m_49965_().m_61056_().size();
        }
        ObjectLinkedOpenHashSet<ResourceLocation> topLevel = new ObjectLinkedOpenHashSet<>(
                stateCount + BuiltInRegistries.f_257033_.m_13562_());
        ObjectOpenHashSet<String> namespaces = new ObjectOpenHashSet<>(ModList.get().size());
        List<Map.Entry<ResourceKey<Block>, Block>> blockEntries =
                new ArrayList<>(BuiltInRegistries.f_256975_.m_6579_());
        int totalBlocks = blockEntries.size();
        if (totalBlocks <= PARALLEL_BLOCK_CHUNK) {
            ModelLocationBuilder builder = new ModelLocationBuilder();
            for (int i = 0; i < totalBlocks; i++) {
                Map.Entry<ResourceKey<Block>, Block> entry = blockEntries.get(i);
                ResourceLocation location = entry.getKey().m_135782_();
                builder.generateForBlock(topLevel, entry.getValue(), location);
                namespaces.add(location.m_135827_());
            }
        } else {
            int chunkCount = (totalBlocks + PARALLEL_BLOCK_CHUNK - 1) / PARALLEL_BLOCK_CHUNK;
            @SuppressWarnings("unchecked")
            ObjectLinkedOpenHashSet<ResourceLocation>[] chunkLocs = new ObjectLinkedOpenHashSet[chunkCount];
            @SuppressWarnings("unchecked")
            ObjectOpenHashSet<String>[] chunkNamespaces = new ObjectOpenHashSet[chunkCount];
            List<CompletableFuture<Void>> futures = new ArrayList<>(chunkCount);
            for (int c = 0; c < chunkCount; c++) {
                final int chunkIndex = c;
                final int from = c * PARALLEL_BLOCK_CHUNK;
                final int to = Math.min(totalBlocks, from + PARALLEL_BLOCK_CHUNK);
                futures.add(CompletableFuture.runAsync(() -> {
                    ObjectLinkedOpenHashSet<ResourceLocation> localLocs = new ObjectLinkedOpenHashSet<>();
                    ObjectOpenHashSet<String> localNs = new ObjectOpenHashSet<>();
                    ModelLocationBuilder builder = new ModelLocationBuilder();
                    for (int i = from; i < to; i++) {
                        Map.Entry<ResourceKey<Block>, Block> entry = blockEntries.get(i);
                        ResourceLocation location = entry.getKey().m_135782_();
                        builder.generateForBlock(localLocs, entry.getValue(), location);
                        localNs.add(location.m_135827_());
                    }
                    chunkLocs[chunkIndex] = localLocs;
                    chunkNamespaces[chunkIndex] = localNs;
                }, ForkJoinPool.commonPool()));
            }
            for (int c = 0; c < chunkCount; c++) {
                futures.get(c).join();
            }
            for (int c = 0; c < chunkCount; c++) {
                topLevel.addAll(chunkLocs[c]);
                namespaces.addAll(chunkNamespaces[c]);
            }
        }
        for (ResourceLocation itemLocation : BuiltInRegistries.f_257033_.m_6566_()) {
            topLevel.add(new net.minecraft.client.resources.model.ModelResourceLocation(itemLocation, "inventory"));
            namespaces.add(itemLocation.m_135827_());
        }
        return new PrewarmData(topLevel, namespaces);
    }

    static void resetForTest() {
        synchronized (ModelBakePerf.class) {
            prewarmFuture = null;
            cachedData = new SoftReference<>(null);
            ACTIVE_APPLY.remove();
        }
    }

    public static final class PrewarmData {
        public final ObjectLinkedOpenHashSet<ResourceLocation> topLevelModelLocations;
        public final ObjectOpenHashSet<String> namespacesWithModels;

        public PrewarmData(
                ObjectLinkedOpenHashSet<ResourceLocation> topLevelModelLocations,
                ObjectOpenHashSet<String> namespacesWithModels) {
            this.topLevelModelLocations = topLevelModelLocations;
            this.namespacesWithModels = namespacesWithModels;
        }
    }
}
