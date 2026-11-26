package dev.sunlit.fishvisuals.mixin;

import net.minecraft.client.renderer.block.BlockModelShaper;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Anchor mixin on {@link BlockModelShaper} so {@code ClientPostApplyPatches.patchBlockModelShaperCore}
 * accelerates {@code statePropertiesToString} and ModernFix dynamic model cache clearing even when
 * Optimised Block Entities is not installed.
 */
@Mixin(value = BlockModelShaper.class, priority = 2000)
public abstract class PerfBlockModelShaperMixin {
}
