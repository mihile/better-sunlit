package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.WaterMediaPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.watermedia.WaterMedia;
import org.watermedia.loaders.ForgeMCLoader;

/**
 * Runs {@link WaterMedia#start()} asynchronously during {@link ForgeMCLoader} mod construction.
 */
@Mixin(value = ForgeMCLoader.class, remap = false)
public abstract class PerfWaterMediaLoaderMixin {
    @Redirect(
            method = "<init>()V",
            at = @At(value = "INVOKE", target = "Lorg/watermedia/WaterMedia;start()V"))
    private void sunlit$asyncWaterMediaStart(WaterMedia instance) {
        WaterMediaPerf.startAsync(instance);
    }
}
