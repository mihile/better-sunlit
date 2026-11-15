package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "journeymap.client.texture.ImageUtil", remap = false)
public abstract class PerfJourneyMapImageUtilMixin {
    /**
     * @author better-sunlit
     * @reason Allocate zeroed RGBA pixels via calloc instead of allocating width*height int[4] arrays via RGB.toRgba
     */
    @Overwrite(remap = false)
    public static NativeImage getNewBlankImage(int width, int height) {
        return new NativeImage(width, height, true);
    }
}
