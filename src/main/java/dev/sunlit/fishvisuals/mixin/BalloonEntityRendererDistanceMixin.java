package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.fishvisuals.BalloonTracking;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = EntityRenderer.class, remap = false)
public abstract class BalloonEntityRendererDistanceMixin {
    @Redirect(
            method = "m_5523_(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;m_6000_(DDD)Z"))
    private boolean sunlit$useServerBalloonRange(Entity entity, double x, double y, double z) {
        // The server already bounds balloon visibility; leave the renderer's frustum test in place.
        return entity.m_6000_(x, y, z) || BalloonTracking.isBalloon(entity);
    }
}
