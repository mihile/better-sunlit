package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.CrashReportPreloadPerf;
import net.minecraft.SystemReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips hardware probing for the throwaway startup report, keeping real crash reports complete.
 */
@Mixin(value = SystemReport.class, remap = false)
public abstract class PerfSystemReportPreloadMixin {
    @Inject(method = "m_143564_", at = @At("HEAD"), cancellable = true)
    private void sunlit$deferPreloadHardwareProbe(CallbackInfo ci) {
        if (CrashReportPreloadPerf.shouldSkipSyncHardwareProbe()) {
            ci.cancel();
        }
    }
}
