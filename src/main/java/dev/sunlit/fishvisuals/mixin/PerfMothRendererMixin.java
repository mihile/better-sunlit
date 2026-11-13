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
@Mixin(targets = "com.cicada.longwings.client.renderers.ButterflyEntityRenderer$MothEntityRenderer", remap = false)
public abstract class PerfMothRendererMixin {
    @Inject(method = "getTextureLocation(Lcom/cicada/longwings/entity/ButterflyEntity$MothEntity;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$getTextureLocationCached(ButterflyEntity.MothEntity entity,
                                                 CallbackInfoReturnable<ResourceLocation> cir) {
        cir.setReturnValue(LongwingsClientPerf.getMothTextureLocation(entity.getVariant()));
    }

    @Redirect(
            method = "render(Lcom/cicada/longwings/entity/ButterflyEntity$MothEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;matches(Ljava/lang/String;)Z"),
            require = 1,
            remap = false)
    private boolean sunlit$fastMatches(String candidate, String pattern) {
        return LongwingsVariantPerf.matches(candidate, pattern);
    }
}
