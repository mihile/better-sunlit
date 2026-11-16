package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.SpriteMipmapPerf;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Parallelizes per-sprite mipmap generation inside {@link SpriteLoader}.
 */
@Mixin(value = SpriteLoader.class, remap = false)
public abstract class PerfSpriteLoaderMipmapMixin {
    @Inject(method = "m_246625_(Ljava/util/Map;I)V", at = @At("HEAD"), cancellable = true)
    private static void sunlit$parallelMipmaps(
            Map<ResourceLocation, TextureAtlasSprite> sprites,
            int mipLevel,
            CallbackInfo ci) {
        ci.cancel();
        SpriteMipmapPerf.generateMipLevelsParallel(sprites, mipLevel);
    }
}
