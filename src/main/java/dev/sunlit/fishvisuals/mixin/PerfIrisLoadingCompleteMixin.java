package dev.sunlit.fishvisuals.mixin;

import net.irisshaders.iris.Iris;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Defers Iris's eager Overworld shader pipeline compilation out of TitleScreen startup so shaders
 * compile on actual world entry ({@code iris$resetPipeline}) when needed.
 */
@Mixin(value = Iris.class, remap = false)
public abstract class PerfIrisLoadingCompleteMixin {
    @Inject(method = "onLoadingComplete", at = @At("HEAD"), cancellable = true)
    private static void sunlit$deferInitialShaderPipeline(CallbackInfo ci) {
        ci.cancel();
    }
}
