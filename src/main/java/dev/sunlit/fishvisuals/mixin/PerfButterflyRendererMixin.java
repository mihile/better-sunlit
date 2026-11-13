package dev.sunlit.fishvisuals.mixin;

import com.cicada.longwings.entity.ButterflyEntity;
import dev.sunlit.bettersunlit.client.LongwingsClientPerf;
import dev.sunlit.bettersunlit.perf.LongwingsVariantPerf;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.cicada.longwings.client.renderers.ButterflyEntityRenderer", remap = false)
public abstract class PerfButterflyRendererMixin {
    @Inject(method = "hasCustomTexture(Lnet/minecraft/resources/ResourceLocation;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private static void sunlit$hasCustomTextureCached(ResourceLocation location,
                                                      CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(LongwingsClientPerf.hasCustomTexture(location));
    }

    @Inject(method = "getTextureLocation(Lcom/cicada/longwings/entity/ButterflyEntity;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$getTextureLocationCached(ButterflyEntity entity,
                                                 CallbackInfoReturnable<ResourceLocation> cir) {
        cir.setReturnValue(LongwingsClientPerf.getButterflyTextureLocation(entity.getVariant()));
    }

    @Redirect(
            method = "render(Lcom/cicada/longwings/entity/ButterflyEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;matches(Ljava/lang/String;)Z"),
            require = 1,
            remap = false)
    private boolean sunlit$fastMatches(String candidate, String pattern) {
        return LongwingsVariantPerf.matches(candidate, pattern);
    }
}
