package dev.sunlit.fishvisuals.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sunlit.bettersunlit.client.RottenCreaturesModelCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

@Pseudo
@Mixin(targets = "traben.entity_model_features.EMFManager", remap = false)
public abstract class RottenCreaturesModelCompatMixin {
    @WrapOperation(
            method = "getJemDataWithDirectory",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/ResourceManager;"
                    + "m_213713_(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;"),
            require = 1)
    private static Optional<Resource> sunlit$repairRottenCreaturesModel(
            ResourceManager manager, ResourceLocation location, Operation<Optional<Resource>> original) {
        return RottenCreaturesModelCompat.repair(manager, location, original.call(manager, location));
    }
}
