package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.platform.TextureUtil;
import dev.sunlit.bettersunlit.client.TextureReadPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

@Mixin(value = TextureUtil.class, remap = false)
public abstract class PerfTextureUtilMixin {
    @Inject(
            method = "readResource(Ljava/io/InputStream;)Ljava/nio/ByteBuffer;",
            at = @At("HEAD"),
            cancellable = true)
    private static void sunlit$readResourceFast(InputStream input, CallbackInfoReturnable<ByteBuffer> cir)
            throws IOException {
        if (input != null) {
            cir.setReturnValue(TextureReadPerf.readResource(input));
        }
    }
}
