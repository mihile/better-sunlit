package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.IrisAstCachePerf;
import io.github.douira.glsl_transformer.ast.transform.EnumASTTransformer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers the actual parser owner only after Iris has initialized it. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", priority = 2000, remap = false)
public abstract class PerfTransformPatcherMixin {
    @Shadow @Final private static EnumASTTransformer<?, ?> transformer;

    @Inject(method = "<clinit>()V", at = @At("RETURN"))
    private static void sunlit$registerParser(CallbackInfo ci) {
        IrisAstCachePerf.registerTransformer(transformer);
    }
}
