package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.fishvisuals.client.CaughtFishPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "software.bernie.geckolib.model.GeoModel", remap = false)
public abstract class CaughtFishGeoModelMixin {
    @Inject(method = "handleAnimations", at = @At("HEAD"), cancellable = true, require = 0)
    private void sunlit$skipSwimForCatch(CallbackInfo callback) {
        if (CaughtFishPose.isActive()) callback.cancel();
    }
}
