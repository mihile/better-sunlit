package dev.sunlit.fishvisuals.mixin;

import net.minecraft.client.renderer.texture.MipmapGenerator;
import org.spongepowered.asm.mixin.Mixin;

/** Adds the bulk generator only after the verified Iris color overwrite has been applied. */
@Mixin(value = MipmapGenerator.class, priority = 500, remap = false)
public abstract class PerfMipmapFastMixin {
}
