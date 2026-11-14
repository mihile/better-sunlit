package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.raphimc.immediatelyfast.compat.IrisCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Eliminates {@code Collections.singleton(...)} / iterator allocations from {@code getBufferBuilder}
 * in ImmediatelyFast's {@code BatchableBufferSource.endBatch(RenderType)} and {@code close()}, and
 * replaces {@code containsKey + get} double map lookups in {@code getOrCreateBufferBuilder}.
 */
@Pseudo
@Mixin(targets = "net.raphimc.immediatelyfast.feature.core.BatchableBufferSource", priority = 2000, remap = false)
public abstract class PerfBatchableBufferSourceMixin extends MultiBufferSource.BufferSource implements AutoCloseable {
    @Shadow
    protected Map<RenderType, ReferenceSet<BufferBuilder>> fallbackBuffers;

    @Shadow
    protected Set<RenderType> activeLayers;

    @Shadow
    protected boolean drawFallbackLayersFirst;

    protected PerfBatchableBufferSourceMixin(BufferBuilder fallback, Map<RenderType, BufferBuilder> fixedBuffers) {
        super(fallback, fixedBuffers);
    }

    @Shadow
    protected abstract int getLayerOrder(RenderType layer);

    @Unique
    private static final int SUNLIT_SET_POOL_LIMIT = 64;

    @Unique
    private static final int SUNLIT_RECYCLE_MAX_ENTRIES = 64;

    @Unique
    private final java.util.ArrayDeque<ReferenceSet<BufferBuilder>> sunlit$setPool = new java.util.ArrayDeque<>(16);

    @Unique
    private RenderType[] sunlit$sortScratch;

    @Unique
    private final java.util.Comparator<RenderType> sunlit$layerOrderComparator =
            (a, b) -> Integer.compare(this.getLayerOrder(a), this.getLayerOrder(b));

    @Unique
    private ReferenceSet<BufferBuilder> sunlit$borrowSet() {
        ReferenceSet<BufferBuilder> set = this.sunlit$setPool.pollLast();
        return set != null ? set : new it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet<>();
    }

    @Unique
    private void sunlit$recycleSet(ReferenceSet<BufferBuilder> set) {
        if (set == null) {
            return;
        }
        boolean reusable = set.size() <= SUNLIT_RECYCLE_MAX_ENTRIES;
        set.clear();
        if (reusable && this.sunlit$setPool.size() < SUNLIT_SET_POOL_LIMIT) {
            this.sunlit$setPool.addLast(set);
        }
    }

    @Unique
    private void sunlit$returnScratch(RenderType[] layers, int usedCount) {
        if (usedCount > 0) {
            java.util.Arrays.fill(layers, 0, usedCount, null);
        }
        if (this.sunlit$sortScratch == null || layers.length > this.sunlit$sortScratch.length) {
            this.sunlit$sortScratch = layers;
        }
    }

    /**
     * @author better-sunlit
     * @reason Pool ReferenceLinkedOpenHashSet instances in fallbackBuffers instead of allocating per batch
     */
    @Overwrite(remap = false)
    protected BufferBuilder addNewFallbackBuffer(RenderType layer) {
        BufferBuilder bufferBuilder = net.raphimc.immediatelyfast.feature.core.BufferBuilderPool.get();
        ReferenceSet<BufferBuilder> set = this.fallbackBuffers.get(layer);
        if (set == null) {
            set = this.sunlit$borrowSet();
            this.fallbackBuffers.put(layer, set);
        }
        set.add(bufferBuilder);
        return bufferBuilder;
    }

    /**
     * @author better-sunlit
     * @reason Reuse scratch array and Comparator when flushing fallback layers
     */
    @Overwrite(remap = false)
    public void m_173043_() {
        this.f_109906_ = Optional.empty();
        this.drawFallbackLayersFirst = false;
        int activeCount = this.activeLayers.size();
        if (activeCount == 0) {
            return;
        }
        RenderType[] layers = this.sunlit$sortScratch;
        this.sunlit$sortScratch = null;
        if (layers == null || layers.length < activeCount) {
            layers = new RenderType[activeCount];
        }
        int layerCount = 0;
        for (RenderType layer : this.activeLayers) {
            if (!this.f_109905_.containsKey(layer)) {
                layers[layerCount++] = layer;
            }
        }
        if (layerCount == 0) {
            this.sunlit$returnScratch(layers, 0);
            return;
        }
        try {
            if (layerCount > 1) {
                java.util.Arrays.sort(layers, 0, layerCount, this.sunlit$layerOrderComparator);
            }
            for (int i = 0; i < layerCount; i++) {
                this.m_109912_(layers[i]);
            }
        } finally {
            this.sunlit$returnScratch(layers, layerCount);
        }
    }

    /**
     * @author better-sunlit
     * @reason Avoid SingletonSet/iterator allocation, redundant fallback map remove/lookup, and pool fallback sets in endBatch
     */
    @Overwrite(remap = false)
    public void m_109912_(RenderType layer) {
        if (this.drawFallbackLayersFirst) {
            this.m_173043_();
        }
        if (IrisCompat.IRIS_LOADED && !IrisCompat.isRenderingLevel.getAsBoolean()) {
            IrisCompat.renderWithExtendedVertexFormat.accept(false);
        }
        this.activeLayers.remove(layer);
        ReferenceSet<BufferBuilder> fallback = this.fallbackBuffers.get(layer);
        if (fallback != null) {
            for (BufferBuilder bufferBuilder : fallback) {
                if (bufferBuilder != null) {
                    layer.m_276775_(bufferBuilder, RenderSystem.getVertexSorting());
                }
            }
            ReferenceSet<BufferBuilder> removed = this.fallbackBuffers.remove(layer);
            this.sunlit$recycleSet(removed);
        } else {
            BufferBuilder bufferBuilder = this.f_109905_.get(layer);
            if (bufferBuilder != null) {
                layer.m_276775_(bufferBuilder, RenderSystem.getVertexSorting());
            }
        }
        if (IrisCompat.IRIS_LOADED && !IrisCompat.isRenderingLevel.getAsBoolean()) {
            IrisCompat.renderWithExtendedVertexFormat.accept(true);
        }
    }

    /**
     * @author better-sunlit
     * @reason Avoid SingletonSet/iterator allocation and recycle fallback ReferenceSets in close()
     */
    @Override
    @Overwrite(remap = false)
    public void close() {
        this.f_109906_ = Optional.empty();
        this.drawFallbackLayersFirst = false;
        for (RenderType layer : this.activeLayers) {
            ReferenceSet<BufferBuilder> fallback = this.fallbackBuffers.get(layer);
            if (fallback != null) {
                for (BufferBuilder bufferBuilder : fallback) {
                    bufferBuilder.m_231175_().m_231200_();
                }
            } else {
                BufferBuilder bufferBuilder = this.f_109905_.get(layer);
                if (bufferBuilder != null) {
                    bufferBuilder.m_231175_().m_231200_();
                }
            }
        }
        this.activeLayers.clear();
        for (ReferenceSet<BufferBuilder> set : this.fallbackBuffers.values()) {
            this.sunlit$recycleSet(set);
        }
        this.fallbackBuffers.clear();
    }

    /**
     * @author better-sunlit
     * @reason Single map lookup on fixedBuffers and fallbackBuffers
     */
    @Overwrite(remap = false)
    protected BufferBuilder getOrCreateBufferBuilder(RenderType layer) {
        if (!layer.m_234326_()) {
            return this.addNewFallbackBuffer(layer);
        }
        BufferBuilder fixed = this.f_109905_.get(layer);
        if (fixed != null) {
            return fixed;
        }
        ReferenceSet<BufferBuilder> fallback = this.fallbackBuffers.get(layer);
        if (fallback != null && !fallback.isEmpty()) {
            return fallback.iterator().next();
        }
        return this.addNewFallbackBuffer(layer);
    }
}
