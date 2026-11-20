package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.ObjectHolderPerf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.RegistryObject;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Registers each {@link RegistryObject}'s update handler under its known target registry name so
 * {@link ObjectHolderPerf} avoids scanning unrelated registries during startup baking.
 */
@Mixin(value = RegistryObject.class, remap = false)
public abstract class PerfRegistryObjectMixin<T> {
    @Shadow
    @Final
    private ResourceKey<T> key;

    @Shadow
    @Final
    boolean optionalRegistry;

    @Redirect(
            method = "<init>(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraftforge/registries/IForgeRegistry;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/registries/ObjectHolderRegistry;addHandler(Ljava/util/function/Consumer;)V"))
    private void sunlit$addForgeRegistryHandler(Consumer<Predicate<ResourceLocation>> consumer) {
        ObjectHolderPerf.addTargetedHandler(this.key.m_211136_(), consumer, false);
    }

    @Redirect(
            method = "<init>(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/registries/ObjectHolderRegistry;addHandler(Ljava/util/function/Consumer;)V"))
    private void sunlit$addNamedRegistryHandler(Consumer<Predicate<ResourceLocation>> consumer) {
        ObjectHolderPerf.addTargetedHandler(this.key.m_211136_(), consumer, !this.optionalRegistry);
    }
}
