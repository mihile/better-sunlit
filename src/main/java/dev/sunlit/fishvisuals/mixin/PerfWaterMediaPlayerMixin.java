package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.WaterMediaPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.watermedia.api.player.PlayerAPI;

/**
 * Ensures background {@code WaterMedia.start()} has completed before {@link PlayerAPI} consumers read factories.
 */
@Mixin(value = PlayerAPI.class, remap = false)
public abstract class PerfWaterMediaPlayerMixin {
    @Inject(
            method = {
                    "isReady()Z",
                    "getFactory()Lorg/watermedia/videolan4j/factory/MediaPlayerFactory;",
                    "getFactory(Ljava/lang/String;)Lorg/watermedia/videolan4j/factory/MediaPlayerFactory;",
                    "getFactorySoundOnly()Lorg/watermedia/videolan4j/factory/MediaPlayerFactory;",
                    "registerFactory(Ljava/lang/String;[Ljava/lang/String;)Lorg/watermedia/videolan4j/factory/MediaPlayerFactory;"
            },
            at = @At("HEAD"))
    private static void sunlit$awaitWaterMedia(CallbackInfoReturnable<?> cir) {
        WaterMediaPerf.awaitStarted();
    }
}
