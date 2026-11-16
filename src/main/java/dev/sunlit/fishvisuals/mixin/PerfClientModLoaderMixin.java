package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.BootAgentSync;
import dev.sunlit.bettersunlit.client.KubeJsPackPerf;
import dev.sunlit.bettersunlit.client.ModelBakePerf;
import dev.sunlit.bettersunlit.client.StartupClassPreloadPerf;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraftforge.client.loading.ClientModLoader;
import net.minecraftforge.fml.LoadingFailedException;
import net.minecraftforge.fml.ModWorkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executor;

/**
 * Starts KubeJS asset scanning, off-thread model key prewarming, and Mixin class preloading as
 * early as possible during Forge client initialization.
 */
@Mixin(value = ClientModLoader.class, remap = false)
public abstract class PerfClientModLoaderMixin {
    @Shadow
    private static LoadingFailedException error;

    @Inject(method = "begin", at = @At("HEAD"))
    private static void sunlit$scheduleEarlyKubeJsPrefetch(
            Minecraft minecraft,
            PackRepository defaultResourcePacks,
            ReloadableResourceManager mcResourceManager,
            CallbackInfo ci) {
        KubeJsPackPerf.scheduleEarlyClientPrefetch();
        BootAgentSync.scheduleSync();
    }

    @Inject(method = "begin", at = @At("RETURN"))
    private static void sunlit$scheduleEarlyModelPrewarm(
            Minecraft minecraft,
            PackRepository defaultResourcePacks,
            ReloadableResourceManager mcResourceManager,
            CallbackInfo ci) {
        if (error == null) {
            ModelBakePerf.schedulePrewarm();
        }
    }

    @Inject(method = "startModLoading", at = @At("HEAD"))
    private static void sunlit$scheduleEarlyLoadPrewarm(
            ModWorkManager.DrivenExecutor syncExecutor,
            Executor parallelExecutor,
            CallbackInfo ci) {
        KubeJsPackPerf.scheduleEarlyClientPrefetch();
        StartupClassPreloadPerf.schedulePreload();
    }
}
