package dev.sunlit.fishvisuals.mixin;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Triggers post-apply fast-path injection into Emberbium's
 * {@code embeddium$processTransparentImages} via {@code ClientPostApplyPatches}.
 */
@Mixin(value = SpriteContents.class, priority = 500, remap = false)
public abstract class PerfSpriteContentsTransparencyMixin {
}
