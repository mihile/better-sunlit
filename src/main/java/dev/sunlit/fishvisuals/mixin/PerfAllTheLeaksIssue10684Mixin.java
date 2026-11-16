package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.AllTheLeaksPerf;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;

/**
 * Offloads AllTheLeaks {@code Issue10684.closeDanglingJars} off the main render thread's
 * {@code DeferredWorkQueue.runTasks()} pass, since it only closes {@code UnionFileSystem} handles.
 */
@Pseudo
@Mixin(targets = "dev.uncandango.alltheleaks.leaks.common.mods.forge.Issue10684", remap = false)
public abstract class PerfAllTheLeaksIssue10684Mixin {
    @Redirect(
            method = "closeDanglingJars",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/fml/event/lifecycle/FMLLoadCompleteEvent;enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private CompletableFuture<Void> sunlit$deferCloseDanglingJars(FMLLoadCompleteEvent event, Runnable work) {
        return AllTheLeaksPerf.deferJarCleanup(work);
    }
}
