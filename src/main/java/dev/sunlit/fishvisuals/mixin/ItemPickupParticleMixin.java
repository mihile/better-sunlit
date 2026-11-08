package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sunlit.fishvisuals.client.ClientFishVisuals;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ItemPickupParticle;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla copies ItemEntities into an item-icon pickup particle; hide just that copy for presented fish. */
@Mixin(value=ItemPickupParticle.class,remap=false)
public abstract class ItemPickupParticleMixin {
    @Unique private boolean sunlit$instantFishPickup;
    @Inject(method="<init>(Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;Lnet/minecraft/client/renderer/RenderBuffers;Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;)V",at=@At("RETURN"),require=1,remap=false)
    private void sunlit$rememberFish(EntityRenderDispatcher dispatcher,RenderBuffers buffers,ClientLevel level,
            Entity source,Entity target,Vec3 motion,CallbackInfo ci) {
        sunlit$instantFishPickup=ClientFishVisuals.hasVisual(source);
    }
    @Inject(method="m_5744_",at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private void sunlit$hideItemIcon(VertexConsumer vertices,Camera camera,float partial,CallbackInfo ci) {
        if(sunlit$instantFishPickup) ci.cancel();
    }
}
