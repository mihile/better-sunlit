package dev.sunlit.fishvisuals.mixin;

import net.minecraft.client.model.PigModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Snow Pig textures, including glowing eyes, use the vanilla pig UV layout. */
@Pseudo
@Mixin(targets = "com.uraneptus.snowpig.client.entity.render.SnowPigRender", remap = false)
public abstract class SnowPigModelCompatMixin {
    @Redirect(
            method = "<init>(Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;"
                    + "m_174023_(Lnet/minecraft/client/model/geom/ModelLayerLocation;)"
                    + "Lnet/minecraft/client/model/geom/ModelPart;"),
            require = 2, allow = 2)
    private static ModelPart sunlit$bakeSnowPigLayer(EntityRendererProvider.Context context, ModelLayerLocation layer) {
        if (layer == ModelLayers.f_171205_) {
            return PigModel.m_170800_(CubeDeformation.f_171458_).m_171564_();
        }
        if (layer == ModelLayers.f_171160_) {
            return PigModel.m_170800_(new CubeDeformation(0.5F)).m_171564_();
        }
        return context.m_174023_(layer);
    }
}
