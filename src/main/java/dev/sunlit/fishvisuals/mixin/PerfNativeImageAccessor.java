package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = NativeImage.class, remap = false)
public interface PerfNativeImageAccessor {
    @Accessor("f_84964_")
    long sunlit$getPixels();
}
