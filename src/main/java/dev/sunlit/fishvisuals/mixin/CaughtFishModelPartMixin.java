package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sunlit.fishvisuals.client.CaughtFishPose;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ModelPart.class, remap = false)
public abstract class CaughtFishModelPartMixin {
    @Inject(method = "m_104299_", at = @At("HEAD"), cancellable = true)
    private void sunlit$poseCaughtFish(PoseStack stack, CallbackInfo callback) {
        if (CaughtFishPose.apply((ModelPart)(Object)this, stack)) callback.cancel();
    }
}
