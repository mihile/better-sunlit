package dev.sunlit.fishvisuals.mixin;

import dev.isxander.yacl3.gui.image.YACLImageReloadListener;
import dev.sunlit.bettersunlit.client.YaclImagePerf;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Decodes YACL config screen preview animations asynchronously without blocking {@code LoadingOverlay}.
 */
@Mixin(value = YACLImageReloadListener.class, remap = false)
public abstract class PerfYaclImageReloadMixin {
    @SuppressWarnings("rawtypes")
    @Shadow
    private CompletableFuture prepare(ResourceManager resourceManager, Executor backgroundExecutor) {
        throw new AssertionError();
    }

    @SuppressWarnings("rawtypes")
    @Shadow
    private CompletableFuture apply(List preparations, Executor gameExecutor) {
        throw new AssertionError();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(
            method = "m_5540_(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/util/profiling/ProfilerFiller;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true)
    private void sunlit$asyncYaclImageReload(
            PreparableReloadListener.PreparationBarrier barrier,
            ResourceManager resourceManager,
            ProfilerFiller prepareProfiler,
            ProfilerFiller applyProfiler,
            Executor backgroundExecutor,
            Executor gameExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        YaclImagePerf.runAsync(() ->
                prepare(resourceManager, YaclImagePerf.executor())
                        .thenCompose(list -> apply((List) list, gameExecutor)));
        cir.setReturnValue(barrier.m_6769_(null));
    }
}
