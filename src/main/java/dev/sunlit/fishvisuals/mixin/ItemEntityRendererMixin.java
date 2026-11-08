package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sunlit.fishvisuals.client.ClientFishVisuals;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ItemEntityRenderer.class, remap = false)
public abstract class ItemEntityRendererMixin {
    @Inject(method = "m_7392_(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$renderCatch(ItemEntity item, float yaw, float partialTick, PoseStack pose,
                                    MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (ClientFishVisuals.render(item, partialTick, pose, buffers, light)) ci.cancel();
    }
}
