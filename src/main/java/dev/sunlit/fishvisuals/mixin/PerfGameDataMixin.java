package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.StartupClassPreloadPerf;
import dev.sunlit.bettersunlit.perf.StateDefinitionPerf;
import net.minecraftforge.registries.GameData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Triggers off-thread Mixin and renderer class preloading right as {@code LOAD_REGISTRIES} starts
 * on the main thread, giving background workers the entire single-threaded registry window.
 */
@Mixin(value = GameData.class, remap = false)
public abstract class PerfGameDataMixin {
    @Inject(method = "unfreezeData", at = @At("HEAD"))
    private static void sunlit$runPostConstructHooks(CallbackInfo ci) {
        StateDefinitionPerf.runPostConstructHooks();
    }

    @Inject(method = "postRegisterEvents", at = @At("HEAD"))
    private static void sunlit$scheduleRegistryWindowPreload(CallbackInfo ci) {
        StartupClassPreloadPerf.schedulePreload();
    }
}
