package dev.sunlit.fishvisuals.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "tschipp.carryon.client.render.CarriedObjectRender", remap = false)
public abstract class CarryOnRenderPoseMixin {
    @ModifyArg(method = {"drawFirstPersonEntity", "drawThirdPerson"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;"
                            + "m_114384_(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
            index = 0, require = 2, allow = 2)
    private static Entity sunlit$stabilizeCarriedPose(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return entity;

        // Carry On renders at partial tick zero, but EMF uses the client's
        // actual interpolation factor. Preserve the pose Carry On set for zero.
        living.m_146922_(living.f_19859_);
        living.m_146926_(living.f_19860_);
        living.f_20883_ = living.f_20884_;
        living.f_20886_ = living.f_20885_; // Carry On reset the current head angle.

        // The rendered copy does not tick and may have been repositioned after
        // being loaded from NBT. Align both previous positions without ticking it.
        living.f_19854_ = living.f_19790_ = living.m_20185_();
        living.f_19855_ = living.f_19791_ = living.m_20186_();
        living.f_19856_ = living.f_19792_ = living.m_20189_();
        living.f_19797_ = (int) living.m_9236_().m_46467_();
        return entity;
    }
}
