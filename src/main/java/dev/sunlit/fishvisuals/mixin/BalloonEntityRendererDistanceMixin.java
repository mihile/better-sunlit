package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.EmfRenderPerf;
import dev.sunlit.fishvisuals.BalloonTracking;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityRenderer.class, remap = false)
public abstract class BalloonEntityRendererDistanceMixin {
    @Redirect(
            method = "m_5523_(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;m_6000_(DDD)Z"))
    private boolean sunlit$useServerBalloonRange(Entity entity, double x, double y, double z) {
        // The server already bounds balloon visibility; leave the renderer's frustum test in place.
        return entity.m_6000_(x, y, z) || BalloonTracking.isBalloon(entity);
    }

    @Inject(
            method = "m_5523_(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;m_6921_()Lnet/minecraft/world/phys/AABB;"),
            cancellable = true)
    private void sunlit$fastFrustumCheck(
            Entity entity, Frustum frustum, double camX, double camY, double camZ,
            CallbackInfoReturnable<Boolean> cir) {
        int res = EmfRenderPerf.tryFastEntityFrustumCheck(entity, frustum);
        if (res >= 0) {
            cir.setReturnValue(res != 0);
        }
    }
}
