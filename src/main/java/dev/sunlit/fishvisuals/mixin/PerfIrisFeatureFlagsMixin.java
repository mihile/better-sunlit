package dev.sunlit.fishvisuals.mixin;

import net.irisshaders.iris.features.FeatureFlags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caches {@link FeatureFlags#isUsable()} on each enum constant after Render-thread evaluation so
 * background shaderpack parsing does not query thread-local LWJGL {@code GL.getCapabilities()}.
 */
@Mixin(value = FeatureFlags.class, remap = false)
public abstract class PerfIrisFeatureFlagsMixin {
    @Unique
    private byte sunlit$usableState;

    @Inject(method = "isUsable()Z", at = @At("HEAD"), cancellable = true)
    private void sunlit$readCachedUsable(CallbackInfoReturnable<Boolean> cir) {
        byte state = this.sunlit$usableState;
        if (state != 0) {
            cir.setReturnValue(state == 2);
        }
    }

    @Inject(method = "isUsable()Z", at = @At("RETURN"))
    private void sunlit$storeCachedUsable(CallbackInfoReturnable<Boolean> cir) {
        this.sunlit$usableState = (byte) (cir.getReturnValueZ() ? 2 : 1);
    }
}
