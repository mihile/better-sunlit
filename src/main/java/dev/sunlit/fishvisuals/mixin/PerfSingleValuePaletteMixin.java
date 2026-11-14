package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ParticleRainClientPerf;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Exposes {@code SingleValuePalette.value} directly without exception allocation when reading
 * single-biome chunk sections.
 */
@Mixin(value = SingleValuePalette.class, priority = 900, remap = false)
public abstract class PerfSingleValuePaletteMixin<T> implements ParticleRainClientPerf.SingleValuePaletteBridge {

    @Shadow
    private T f_188204_;

    @Override
    @Unique
    public Object sunlit$paletteSingleValue() {
        return this.f_188204_;
    }
}
