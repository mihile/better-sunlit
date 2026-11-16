package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.KubeJsPackPerf;
import dev.sunlit.bettersunlit.client.ModelBakePerf;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Kicks off static model location precomputation and KubeJS asset prefetching at the start of
 * resource reload, long before {@code ModelBakeEventHelper.<init>} needs top-level locations.
 */
@Mixin(value = ReloadableResourceManager.class, remap = false)
public abstract class PerfModelBakeryPrewarmMixin {
    @Inject(method = "m_142463_", at = @At("HEAD"))
    private void sunlit$scheduleModelBakePrewarm(
            Executor backgroundExecutor,
            Executor gameExecutor,
            CompletableFuture<Unit> waitingFor,
            List<PackResources> packs,
            CallbackInfoReturnable<ReloadInstance> cir) {
        ModelBakePerf.schedulePrewarm();
        KubeJsPackPerf.scheduleEarlyClientPrefetch();
    }
}
