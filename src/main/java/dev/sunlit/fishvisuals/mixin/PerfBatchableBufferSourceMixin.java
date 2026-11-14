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
    public abstract void m_173043_();

    @Shadow
    protected abstract BufferBuilder addNewFallbackBuffer(RenderType layer);

    /**
     * @author better-sunlit
     * @reason Avoid SingletonSet/iterator allocation and redundant fallback map remove/lookup per endBatch
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
            this.fallbackBuffers.remove(layer);
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
     * @reason Avoid SingletonSet/iterator allocation per active layer in close()
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
        if (fallback != null) {
            return fallback.iterator().next();
        }
        return this.addNewFallbackBuffer(layer);
    }
}
