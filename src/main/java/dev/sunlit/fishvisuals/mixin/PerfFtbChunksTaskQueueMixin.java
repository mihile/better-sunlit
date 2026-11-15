package dev.sunlit.fishvisuals.mixin;

import dev.ftb.mods.ftbchunks.client.FTBChunksClientConfig;
import dev.ftb.mods.ftbchunks.client.map.MapTask;
import dev.sunlit.bettersunlit.client.FtbChunksClientPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayDeque;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbchunks.client.ClientTaskQueue", remap = false)
public abstract class PerfFtbChunksTaskQueueMixin {
    @Shadow(remap = false)
    private static ArrayDeque<MapTask> taskQueue;

    /**
     * @author better-sunlit
     * @reason Spread queued client map tasks across ticks with a 2.5ms budget per batch while guaranteeing progress
     */
    @Overwrite(remap = false)
    static void runQueuedTasks() {
        FtbChunksClientPerf.runQueuedTasks(
                taskQueue,
                FTBChunksClientConfig.TASK_QUEUE_MAX.get(),
                FtbChunksClientPerf.DEFAULT_TASK_BUDGET_NS
        );
    }
}
