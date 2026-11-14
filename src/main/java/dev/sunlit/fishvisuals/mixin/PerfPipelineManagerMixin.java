package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.IrisAstCachePerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Trims glsl-transformer's global AST build/parse caches immediately after a new dimension
 * rendering pipeline finishes compiling in {@code PipelineManager.preparePipeline}.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", priority = 2000, remap = false)
public abstract class PerfPipelineManagerMixin {

    @Inject(
            method = "preparePipeline(Lnet/irisshaders/iris/shaderpack/materialmap/NamespacedId;)Lnet/irisshaders/iris/pipeline/WorldRenderingPipeline;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private void sunlit$trimAstCachesOnNewPipeline(CallbackInfoReturnable<?> cir) {
        IrisAstCachePerf.trimAstCaches();
    }
}
