package dev.sunlit.fishvisuals.mixin;

import me.cortex.voxy.client.core.gl.AsyncTextureUploader;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skips Voxy's PBO allocation/mapping overhead for initial atlas uploads while no world is
 * loaded, letting vanilla {@code _texSubImage2D} upload startup sprites directly.
 */
@Mixin(value = AsyncTextureUploader.class, remap = false)
public abstract class PerfVoxyAsyncUploaderMixin {
    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private static void sunlit$skipPboDuringMenuStartup(
            int target,
            int level,
            int xOffset,
            int yOffset,
            int uploadWidth,
            int uploadHeight,
            int glFormat,
            int glType,
            long sourcePixels,
            int sourceWidth,
            int sourceHeight,
            int components,
            CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.m_91087_();
        if (mc == null || mc.f_91073_ == null) {
            cir.setReturnValue(false);
        }
    }
}
