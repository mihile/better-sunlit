package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.TrustedSourcePerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.URL;

/**
 * Downloads Commonality's trusted-source list asynchronously instead of stalling mod loading on
 * an external HTTPS request.
 */
@Pseudo
@Mixin(targets = "xyz.apex.forge.commonality.trust.TrustedSourceList", remap = false)
public abstract class PerfTrustedSourceListMixin {
    @Shadow
    private static boolean enabled;

    @Shadow
    private static boolean init;

    @Inject(method = "downloadTrustedSources()V", at = @At("HEAD"), cancellable = true)
    private static void sunlit$asyncDownload(CallbackInfo ci) {
        init = true;
        TrustedSourcePerf.ensureStarted();
        ci.cancel();
    }

    @Inject(method = "isBlacklisted(Ljava/net/URL;)Z", at = @At("HEAD"), cancellable = true)
    private static void sunlit$checkAsyncBlacklist(URL url, CallbackInfoReturnable<Boolean> cir) {
        init = true;
        TrustedSourcePerf.ensureStarted();
        cir.setReturnValue(enabled && TrustedSourcePerf.isBlacklisted(url));
    }
}
