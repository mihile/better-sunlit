package dev.sunlit.fishvisuals.mixin;

import net.irisshaders.iris.gl.IrisRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caches capability-backed boolean queries in {@link IrisRenderSystem} after Render-thread
 * warmup so background {@code ShaderPack} / {@code ShaderProperties} construction never queries
 * thread-local {@code GL.getCapabilities()}.
 */
@Mixin(value = IrisRenderSystem.class, remap = false)
public abstract class PerfIrisRenderSystemMixin {
    @Unique
    private static volatile byte sunlit$ssboState;
    @Unique
    private static volatile byte sunlit$imageLoadStoreState;
    @Unique
    private static volatile byte sunlit$bufferBlendingState;

    @Inject(method = "supportsSSBO()Z", at = @At("HEAD"), cancellable = true)
    private static void sunlit$readSsbo(CallbackInfoReturnable<Boolean> cir) {
        byte s = sunlit$ssboState;
        if (s != 0) cir.setReturnValue(s == 2);
    }

    @Inject(method = "supportsSSBO()Z", at = @At("RETURN"))
    private static void sunlit$storeSsbo(CallbackInfoReturnable<Boolean> cir) {
        if (sunlit$ssboState == 0) sunlit$ssboState = (byte) (cir.getReturnValueZ() ? 2 : 1);
    }

    @Inject(method = "supportsImageLoadStore()Z", at = @At("HEAD"), cancellable = true)
    private static void sunlit$readImageLoadStore(CallbackInfoReturnable<Boolean> cir) {
        byte s = sunlit$imageLoadStoreState;
        if (s != 0) cir.setReturnValue(s == 2);
    }

    @Inject(method = "supportsImageLoadStore()Z", at = @At("RETURN"))
    private static void sunlit$storeImageLoadStore(CallbackInfoReturnable<Boolean> cir) {
        if (sunlit$imageLoadStoreState == 0) {
            sunlit$imageLoadStoreState = (byte) (cir.getReturnValueZ() ? 2 : 1);
        }
    }

    @Inject(method = "supportsBufferBlending()Z", at = @At("HEAD"), cancellable = true)
    private static void sunlit$readBufferBlending(CallbackInfoReturnable<Boolean> cir) {
        byte s = sunlit$bufferBlendingState;
        if (s != 0) cir.setReturnValue(s == 2);
    }

    @Inject(method = "supportsBufferBlending()Z", at = @At("RETURN"))
    private static void sunlit$storeBufferBlending(CallbackInfoReturnable<Boolean> cir) {
        if (sunlit$bufferBlendingState == 0) {
            sunlit$bufferBlendingState = (byte) (cir.getReturnValueZ() ? 2 : 1);
        }
    }
}
