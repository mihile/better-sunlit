package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.WaterMediaPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.watermedia.api.image.ImageAPI;

/**
 * Ensures background {@code WaterMedia.start()} has completed before {@link ImageAPI} consumers run.
 */
@Mixin(value = ImageAPI.class, remap = false)
public abstract class PerfWaterMediaImageMixin {
    @Inject(
            method = {
                    "failedVLC()Lorg/watermedia/api/image/ImageRenderer;",
                    "failedVLCLandscape()Lorg/watermedia/api/image/ImageRenderer;",
                    "cacheFailedVLC()Lorg/watermedia/api/image/ImageCache;",
                    "cacheFailedVLCLandscape()Lorg/watermedia/api/image/ImageCache;",
                    "loadingGif()Lorg/watermedia/api/image/ImageRenderer;",
                    "blackPicture()Lorg/watermedia/api/image/ImageRenderer;",
                    "cacheLoadingGif()Lorg/watermedia/api/image/ImageCache;",
                    "loadingGif(Ljava/lang/String;)Lorg/watermedia/api/image/ImageRenderer;",
                    "getCache(Ljava/net/URI;Ljava/util/concurrent/Executor;)Lorg/watermedia/api/image/ImageCache;"
            },
            at = @At("HEAD"))
    private static void sunlit$awaitWaterMedia(CallbackInfoReturnable<?> cir) {
        WaterMediaPerf.awaitStarted();
    }
}
