package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.CrashReportPreloadPerf;
import net.minecraft.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks {@code CrashReport.preload()} so only startup preloading defers OSHI hardware probing.
 */
@Mixin(value = CrashReport.class, remap = false)
public abstract class PerfCrashReportPreloadMixin {
    @Inject(method = "m_127529_", at = @At("HEAD"))
    private static void sunlit$enterCrashReportPreload(CallbackInfo ci) {
        CrashReportPreloadPerf.enterPreload();
    }

    @Inject(method = "m_127529_", at = @At("RETURN"))
    private static void sunlit$exitCrashReportPreload(CallbackInfo ci) {
        CrashReportPreloadPerf.exitPreload();
    }
}
