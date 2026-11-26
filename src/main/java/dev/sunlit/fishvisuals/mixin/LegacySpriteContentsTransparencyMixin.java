package dev.sunlit.fishvisuals.mixin;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Triggers post-apply fast-path injection into Embeddium 0.3.31's
 * {@code sodium$fillInTransparentPixelColors} via {@code ClientPostApplyPatches}.
 */
@Mixin(value = SpriteContents.class, priority = 500, remap = false)
public abstract class LegacySpriteContentsTransparencyMixin {
}
