package dev.sunlit.fishvisuals.mixin;

import net.minecraft.client.renderer.block.BlockModelShaper;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Anchor mixin on {@link BlockModelShaper} so {@code EntryPostApplyPatches} runs after ModernFix and
 * Optimised Block Entities have applied, guarding OBE's {@code CallbackInfoReturnable} allocation
 * behind {@code state.hasBlockEntity()}.
 */
@Mixin(value = BlockModelShaper.class, priority = 2000)
public abstract class PerfObeModelMixin {
}
