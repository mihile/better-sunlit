package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.GlslTransformDiskCache;
import dev.sunlit.bettersunlit.client.IrisAstCachePerf;
import io.github.douira.glsl_transformer.ast.transform.EnumASTTransformer;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registers the actual parser owner for AST cache trimming and serves persistent disk-cached
 * Iris GLSL transforms before invoking {@code glsl-transformer}.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", priority = 2000, remap = false)
public abstract class PerfTransformPatcherMixin {
    @Shadow @Final private static EnumASTTransformer<?, ?> transformer;

    @Unique
    private static final ThreadLocal<String> sunlit$pendingIrisKey = new ThreadLocal<>();

    @Inject(method = "<clinit>()V", at = @At("RETURN"))
    private static void sunlit$registerParser(CallbackInfo ci) {
        IrisAstCachePerf.registerTransformer(transformer);
    }

    private static final String TRANSFORM_INTERNAL =
            "transformInternal(Ljava/lang/String;Ljava/util/Map;Lnet/irisshaders/iris/pipeline/transform/parameter/Parameters;)Ljava/util/Map;";

    @Inject(method = TRANSFORM_INTERNAL, at = @At("HEAD"), cancellable = true)
    private static void sunlit$checkDiskCache(
            String name,
            Map<PatchShaderType, String> inputs,
            Parameters parameters,
            CallbackInfoReturnable<Map<PatchShaderType, String>> cir) {
        sunlit$pendingIrisKey.remove();
        boolean debug = Iris.getIrisConfig() != null && Iris.getIrisConfig().areDebugOptionsEnabled();
        String key = GlslTransformDiskCache.keyForIris(name, inputs, parameters, debug);
        if (key == null) {
            return;
        }
        EnumMap<PatchShaderType, String> cached = GlslTransformDiskCache.getIris(key);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }
        sunlit$pendingIrisKey.set(key);
    }

    @Inject(method = TRANSFORM_INTERNAL, at = @At("RETURN"))
    private static void sunlit$storeDiskCache(
            String name,
            Map<PatchShaderType, String> inputs,
            Parameters parameters,
            CallbackInfoReturnable<Map<PatchShaderType, String>> cir) {
        String key = sunlit$pendingIrisKey.get();
        sunlit$pendingIrisKey.remove();
        if (key != null && cir.getReturnValue() != null) {
            GlslTransformDiskCache.putIris(key, cir.getReturnValue());
        }
    }
}
