package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.EmfModelPrebakePerf;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Triggers parallel EMF entity model pre-baking after {@code EntityModelSet} finishes loading
 * roots (including Automobility/Fusion hooks) and serves prebaked models on {@code bakeLayer}.
 */
@Mixin(value = EntityModelSet.class, priority = 2000, remap = false)
public abstract class PerfEntityModelSetMixin {
    @Shadow
    private Map<ModelLayerLocation, LayerDefinition> f_171099_;

    @Inject(method = "m_6213_", at = @At("TAIL"))
    private void sunlit$scheduleEmfPrebake(ResourceManager resourceManager, CallbackInfo ci) {
        EmfModelPrebakePerf.schedulePrebake(resourceManager, this.f_171099_);
    }

    @Inject(method = "m_171103_", at = @At("HEAD"))
    private void sunlit$beginBakeLayer(ModelLayerLocation layerLocation, CallbackInfoReturnable<ModelPart> cir) {
        EmfModelPrebakePerf.beginLookup(layerLocation);
    }

    @Redirect(
            method = "m_171103_",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/geom/builders/LayerDefinition;m_171564_()Lnet/minecraft/client/model/geom/ModelPart;"
            )
    )
    private ModelPart sunlit$takePrebakedOrBake(LayerDefinition definition) {
        return EmfModelPrebakePerf.takeRawAndStageEmfResult(definition);
    }

    @Inject(method = "m_171103_", at = @At("RETURN"))
    private void sunlit$endBakeLayer(ModelLayerLocation layerLocation, CallbackInfoReturnable<ModelPart> cir) {
        EmfModelPrebakePerf.endLookup();
    }
}
