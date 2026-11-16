package dev.sunlit.fishvisuals.mixin;

import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Triggers post-apply cartesian-product replacement in {@code StateDefinition.<init>} via
 * {@code EntryPostApplyPatches} after ModernFix's {@code StateDefinitionMixin} has merged.
 */
@Mixin(value = StateDefinition.class, priority = 2000, remap = false)
public abstract class PerfStateDefinitionMixin {
}
