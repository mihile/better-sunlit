package dev.sunlit.fishvisuals.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Triggers post-apply zero-allocation color matching rewrite in Moonlight's
 * {@code BlocksColorInternal.scanRegistryAndDetectSets} via {@code EntryPostApplyPatches}.
 */
@Mixin(targets = "net.mehvahdjukaar.moonlight.core.set.BlocksColorInternal", priority = 2000, remap = false)
public abstract class PerfBlocksColorMixin {
}
