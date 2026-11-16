package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.CscVersionPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Moves CreateSchematicChecker's startup HTTPS rule version check off {@code modloading-worker}
 * while returning any cached local rule file immediately.
 */
@Pseudo
@Mixin(targets = "com.Pink_Cats.createschematicchecker.online.VersionChecker", remap = false)
public abstract class PerfCscVersionCheckerMixin {
    @Inject(method = "UpdateMainThread()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private static void sunlit$asyncUpdateMainThread(CallbackInfoReturnable<String> cir) {
        if (!CscVersionPerf.isBackgroundThread()) {
            cir.setReturnValue(CscVersionPerf.startAsyncAndGetLocalRule());
        }
    }
}
