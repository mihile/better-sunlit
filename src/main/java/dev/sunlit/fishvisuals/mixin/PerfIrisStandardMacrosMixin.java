package dev.sunlit.fishvisuals.mixin;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caches {@link StandardMacros#createStandardEnvironmentDefines()} after Render-thread evaluation
 * so background shaderpack loading never invokes OpenGL state queries without a bound context.
 */
@Mixin(value = StandardMacros.class, priority = 2000, remap = false)
public abstract class PerfIrisStandardMacrosMixin {
    @Unique
    private static volatile ImmutableList<StringPair> sunlit$cachedDefines;

    @Inject(
            method = "createStandardEnvironmentDefines()Lcom/google/common/collect/ImmutableList;",
            at = @At("HEAD"),
            cancellable = true)
    private static void sunlit$returnCachedDefines(CallbackInfoReturnable<ImmutableList<StringPair>> cir) {
        ImmutableList<StringPair> cached = sunlit$cachedDefines;
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(
            method = "createStandardEnvironmentDefines()Lcom/google/common/collect/ImmutableList;",
            at = @At("RETURN"))
    private static void sunlit$storeCachedDefines(CallbackInfoReturnable<ImmutableList<StringPair>> cir) {
        if (sunlit$cachedDefines == null) {
            ImmutableList<StringPair> ret = cir.getReturnValue();
            if (ret != null) {
                sunlit$cachedDefines = ret;
            }
        }
    }
}
