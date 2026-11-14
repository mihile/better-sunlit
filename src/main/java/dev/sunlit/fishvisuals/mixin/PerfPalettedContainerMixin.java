package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ParticleRainClientPerf;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Reads {@code PalettedContainer.data.palette} lock-free via bytecode patched in
 * {@code ClientPostApplyPatches.patchPalettedContainer}, returning the unique value when backed
 * by {@link net.minecraft.world.level.chunk.SingleValuePalette}.
 */
@Mixin(value = PalettedContainer.class, priority = 900, remap = false)
public abstract class PerfPalettedContainerMixin implements ParticleRainClientPerf.PalettedContainerBridge {

    @Unique
    private Object sunlit$rawPalette() {
        throw new AssertionError("Patched in postApply");
    }

    @Override
    @Unique
    public Object sunlit$containerSingleValue() {
        Object palette = this.sunlit$rawPalette();
        return palette instanceof ParticleRainClientPerf.SingleValuePaletteBridge bridge
                ? bridge.sunlit$paletteSingleValue()
                : null;
    }
}
