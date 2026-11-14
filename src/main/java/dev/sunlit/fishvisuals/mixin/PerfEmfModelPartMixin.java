package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sunlit.bettersunlit.client.EmfRenderPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Propagates Embeddium pose-stack matrix caching across {@code EMFModelPart.renderLikeVanilla}.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.parts.EMFModelPart", priority = 2000, remap = false)
public abstract class PerfEmfModelPartMixin {

    @Redirect(
            method = "renderLikeVanilla(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;m_85836_()V"),
            require = 1)
    private void sunlit$pushWithCache(PoseStack poseStack) {
        EmfRenderPerf.pushWithPoseCaching(poseStack);
    }

    @Redirect(
            method = "renderLikeVanilla(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;m_85849_()V"),
            require = 1)
    private void sunlit$popWithCache(PoseStack poseStack) {
        EmfRenderPerf.popWithPoseCaching(poseStack);
    }
}
