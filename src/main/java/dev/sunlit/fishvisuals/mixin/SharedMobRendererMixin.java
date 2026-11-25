package dev.sunlit.fishvisuals.mixin;

import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sunlit.bettersunlit.client.SharedMobModelCompat;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = EntityRenderers.class, remap = false)
public abstract class SharedMobRendererMixin {
    @WrapOperation(method = "m_257087_", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRendererProvider;"
                    + "m_174009_(Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;)"
                    + "Lnet/minecraft/client/renderer/entity/EntityRenderer;"), require = 1)
    private static EntityRenderer<?> sunlit$createWithOriginalModel(
            EntityRendererProvider<?> provider, EntityRendererProvider.Context context, Operation<EntityRenderer<?>> original,
            ImmutableMap.Builder<?, ?> builder, EntityRendererProvider.Context factoryContext, EntityType<?> type) {
        return SharedMobModelCompat.duringRendererCreation(ForgeRegistries.ENTITY_TYPES.getKey(type),
                () -> original.call(provider, context));
    }
}
