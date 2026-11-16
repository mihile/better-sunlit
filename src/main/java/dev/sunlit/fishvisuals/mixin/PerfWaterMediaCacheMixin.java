package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.WaterMediaPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.watermedia.api.cache.CacheAPI;

import java.net.URI;

/**
 * Ensures background {@code WaterMedia.start()} has completed before {@link CacheAPI} consumers run.
 */
@Mixin(value = CacheAPI.class, remap = false)
public abstract class PerfWaterMediaCacheMixin {
    @Inject(method = "getEntry(Ljava/net/URI;)Lorg/watermedia/api/cache/CacheAPI$Entry;", at = @At("HEAD"))
    private static void sunlit$awaitWaterMediaGet(URI uri, CallbackInfoReturnable<?> cir) {
        WaterMediaPerf.awaitStarted();
    }

    @Inject(
            method = {
                    "saveFile(Ljava/net/URI;Ljava/lang/String;JJ[B)V",
                    "updateEntry(Lorg/watermedia/api/cache/CacheAPI$Entry;)V",
                    "deleteEntry(Ljava/net/URI;)V"
            },
            at = @At("HEAD"))
    private static void sunlit$awaitWaterMediaMutate(CallbackInfo ci) {
        WaterMediaPerf.awaitStarted();
    }
}
