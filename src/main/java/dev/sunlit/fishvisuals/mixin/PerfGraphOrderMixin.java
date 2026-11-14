package dev.sunlit.fishvisuals.mixin;

import de.odysseus.ithaka.digraph.Digraph;
import de.odysseus.ithaka.digraph.util.fas.FeedbackArcSetProvider;
import dev.sunlit.bettersunlit.client.GraphOrderPerf;
import net.irisshaders.batchedentityrendering.impl.TransparencyType;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumMap;
import java.util.List;

/**
 * Replaces per-frame {@code MapDigraph} allocations and boxed graph/FAS traversals in Iris
 * {@code GraphTranslucencyRenderOrderManager} with {@link GraphOrderPerf.PooledRenderDigraph}.
 */
@Pseudo
@Mixin(
        targets = "net.irisshaders.batchedentityrendering.impl.ordering.GraphTranslucencyRenderOrderManager",
        priority = 2000,
        remap = false)
public abstract class PerfGraphOrderMixin {

    @Shadow
    @Final
    private FeedbackArcSetProvider feedbackArcSetProvider;

    @Shadow
    @Final
    private EnumMap<TransparencyType, Digraph<RenderType>> types;

    @Shadow
    @Final
    private EnumMap<TransparencyType, RenderType> currentTypes;

    @Shadow
    private boolean inGroup;

    @Shadow
    private static TransparencyType getTransparencyType(RenderType type) {
        throw new AssertionError();
    }

    @Inject(method = "<init>()V", at = @At("RETURN"))
    private void sunlit$initPooledDigraphs(CallbackInfo ci) {
        GraphOrderPerf.initPooled(this.types);
    }

    /**
     * @author Better Sunlit
     * @reason Avoid OptionalInt boxing and TreeMap lookups on hot render-type begin
     */
    @Overwrite
    public void begin(RenderType type) {
        GraphOrderPerf.recordBegin(this.types, this.currentTypes, getTransparencyType(type), type, this.inGroup);
    }

    /**
     * @author Better Sunlit
     * @reason Reuse pooled array-backed digraphs instead of reallocating MapDigraph each frame
     */
    @Overwrite
    public void reset() {
        GraphOrderPerf.resetAll(this.types);
    }

    /**
     * @author Better Sunlit
     * @reason Reuse pooled array-backed digraphs on per-type reset
     */
    @Overwrite
    public void resetType(TransparencyType type) {
        GraphOrderPerf.resetOne(this.types, type);
    }

    /**
     * @author Better Sunlit
     * @reason Zero-alloc primitive topological ordering and cyclic FAS handling
     */
    @Overwrite
    public List<RenderType> getRenderOrder() {
        return GraphOrderPerf.getRenderOrder(this.types, this.feedbackArcSetProvider);
    }
}
