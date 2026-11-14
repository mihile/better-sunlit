package dev.sunlit.fishvisuals.mixin;

import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Adds a generation-tagged occlusion cache field to {@code BlockBehaviour.BlockStateBase}; the
 * fast-path body of {@code m_60815_()} is rewritten fail-closed in {@code EntryPostApplyPatches}
 * after Copycats+ and PassableFoliage mixins have merged.
 */
@Mixin(value = BlockBehaviour.BlockStateBase.class, priority = 2000)
public abstract class PerfBlockOcclusionMixin {
    @Unique
    public volatile int sunlit$occCache;
}
