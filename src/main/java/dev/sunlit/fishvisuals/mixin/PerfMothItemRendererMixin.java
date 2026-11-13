package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.LongwingsClientPerf;
import dev.sunlit.bettersunlit.perf.LongwingsVariantPerf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.cicada.longwings.client.renderers.ButterflyItemRenderer$MothItemRenderer", remap = false)
public abstract class PerfMothItemRendererMixin {
    @Inject(method = "getTextureLocation(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$getItemTextureCached(ItemStack stack, CallbackInfoReturnable<ResourceLocation> cir) {
        cir.setReturnValue(LongwingsClientPerf.getMothItemTexture(stack));
    }

    @Redirect(
            method = "m_108829_(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;matches(Ljava/lang/String;)Z"),
            require = 1,
            remap = false)
    private boolean sunlit$fastMatches(String candidate, String pattern) {
        return LongwingsVariantPerf.matches(candidate, pattern);
    }
}
