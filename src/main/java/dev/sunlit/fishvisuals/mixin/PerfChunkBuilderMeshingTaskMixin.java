package dev.sunlit.fishvisuals.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Anchor mixin for {@code ChunkBuilderMeshingTask}. The actual bytecode optimization of
 * {@code obe$wrapRenderModel} (skipping {@code new BlockPos} and {@code BlockRenderContext.update}
 * when OBE returns the original {@code ctx.model()}) runs in {@code EntryMixinPlugin.postApply}.
 */
@Pseudo
@Mixin(
        targets = "me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask",
        priority = 2000,
        remap = false
)
public class PerfChunkBuilderMeshingTaskMixin {
}
