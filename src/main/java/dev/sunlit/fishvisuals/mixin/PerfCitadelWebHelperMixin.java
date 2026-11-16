package dev.sunlit.fishvisuals.mixin;

import com.github.alexthe666.citadel.web.WebHelper;
import dev.sunlit.bettersunlit.perf.CitadelWebPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.BufferedReader;

/**
 * Serves Citadel's bundled contributor/patreon resource immediately when present instead of waiting
 * on a synchronous HTTP connection during mod loading.
 */
@Mixin(value = WebHelper.class, remap = false)
public abstract class PerfCitadelWebHelperMixin {
    @Inject(method = "getURLContents", at = @At("HEAD"), cancellable = true)
    private static void sunlit$fastBundledWebContents(
            String urlString,
            String backupFileLocation,
            CallbackInfoReturnable<BufferedReader> cir) {
        BufferedReader bundled = CitadelWebPerf.openBundledReader(backupFileLocation, WebHelper.class);
        if (bundled != null) {
            cir.setReturnValue(bundled);
        }
    }
}
