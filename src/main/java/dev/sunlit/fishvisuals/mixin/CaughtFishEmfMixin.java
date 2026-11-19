package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.fishvisuals.client.CaughtFishPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "traben.entity_model_features.models.parts.EMFModelPartRoot", remap = false)
public abstract class CaughtFishEmfMixin {
    // EMF 3.2.x has animate; 3.3.x also exposes animateNoPause.
    @Inject(method = {"animate()V", "animateNoPause()V"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void sunlit$skipSwimForCatch(CallbackInfo callback) {
        if (CaughtFishPose.isActive()) callback.cancel();
    }
}
