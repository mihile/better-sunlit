package dev.sunlit.fishvisuals.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sunlit.bettersunlit.client.SharedMobModelCompat;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Mixin(value = EntityModelSet.class, remap = false)
public abstract class SharedMobLayerMixin {
    @Shadow
    private Map<ModelLayerLocation, LayerDefinition> f_171099_;

    @WrapMethod(method = "m_171103_", require = 1)
    private ModelPart sunlit$bakeOriginalLayer(ModelLayerLocation layer, Operation<ModelPart> original) {
        if (SharedMobModelCompat.useOriginalLayer(layer)) {
            LayerDefinition definition = f_171099_.get(layer);
            if (definition != null) return definition.m_171564_();
        }
        return original.call(layer);
    }
}
