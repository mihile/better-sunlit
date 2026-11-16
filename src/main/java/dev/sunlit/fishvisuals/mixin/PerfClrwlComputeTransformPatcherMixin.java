package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.GlslTransformDiskCache;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serves persistent disk-cached Colorwheel compute shader transforms before invoking ANTLR.
 */
@Pseudo
@Mixin(targets = "dev.djefrey.colorwheel.compile.transform.ClrwlComputeTransformPatcher", priority = 2000, remap = false)
public abstract class PerfClrwlComputeTransformPatcherMixin {
    @Unique
    private static final ThreadLocal<String> sunlit$pendingComputeKey = new ThreadLocal<>();

    private static final String PATCH_COMPUTE =
            "patchCompute(Ljava/lang/String;ILit/unimi/dsi/fastutil/objects/Object2ObjectMap;)Ljava/lang/String;";

    @Inject(method = PATCH_COMPUTE, at = @At("HEAD"), cancellable = true)
    private static void sunlit$checkComputeCache(
            String source,
            int gbufferType,
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            CallbackInfoReturnable<String> cir) {
        sunlit$pendingComputeKey.remove();
        String key = GlslTransformDiskCache.keyForClrwlString("comp", source, false, gbufferType, textureMap);
        if (key == null) return;
        String cached = GlslTransformDiskCache.getClrwlString(key);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }
        sunlit$pendingComputeKey.set(key);
    }

    @Inject(method = PATCH_COMPUTE, at = @At("RETURN"))
    private static void sunlit$storeComputeCache(
            String source,
            int gbufferType,
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            CallbackInfoReturnable<String> cir) {
        String key = sunlit$pendingComputeKey.get();
        sunlit$pendingComputeKey.remove();
        if (key != null && cir.getReturnValue() != null) {
            GlslTransformDiskCache.putClrwlString(key, cir.getReturnValue());
        }
    }
}
