package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sunlit.fishvisuals.client.CaughtFishPose;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

@Pseudo
@Mixin(targets = "software.bernie.geckolib.util.RenderUtils", remap = false)
public abstract class CaughtFishGeoTransformMixin {
    @Inject(method = "translateMatrixToBone", at = @At("HEAD"), cancellable = true, require = 0)
    private static void sunlit$restOffset(PoseStack stack, CoreGeoBone bone, CallbackInfo callback) {
        if (!CaughtFishPose.isActive()) return;
        var rest = bone.getInitialSnapshot();
        if (rest == null) return;
        stack.m_252880_(-rest.getOffsetX() / 16, rest.getOffsetY() / 16, rest.getOffsetZ() / 16);
        callback.cancel();
    }

    @Inject(method = "rotateMatrixAroundBone", at = @At("HEAD"), cancellable = true, require = 0)
    private static void sunlit$bendJoint(PoseStack stack, CoreGeoBone bone, CallbackInfo callback) {
        if (!CaughtFishPose.isActive()) return;
        var rest = bone.getInitialSnapshot();
        if (rest == null) return;
        float z = rest.getRotZ() + CaughtFishPose.geoGroundRoll(bone.getName());
        if (rest.getRotX() != 0 || rest.getRotY() != 0 || z != 0) {
            stack.m_252781_(new Quaternionf().rotationZYX(z, rest.getRotY(), rest.getRotX()));
        }
        callback.cancel();
    }

    @Inject(method = "scaleMatrixForBone", at = @At("HEAD"), cancellable = true, require = 0)
    private static void sunlit$restScale(PoseStack stack, CoreGeoBone bone, CallbackInfo callback) {
        if (!CaughtFishPose.isActive()) return;
        var rest = bone.getInitialSnapshot();
        if (rest == null) return;
        stack.m_85841_(rest.getScaleX(), rest.getScaleY(), rest.getScaleZ());
        callback.cancel();
    }
}
