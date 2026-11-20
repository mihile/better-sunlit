package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.ObjectHolderPerf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes each {@code ObjectHolderRef}'s target {@link ForgeRegistry} name so
 * {@link ObjectHolderPerf} can bucket it by registry.
 */
@Mixin(targets = "net.minecraftforge.registries.ObjectHolderRef", remap = false)
public abstract class PerfObjectHolderRefMixin implements ObjectHolderPerf.TargetedHolder {
    @Shadow
    @Final
    private ForgeRegistry<?> registry;

    @Override
    public ResourceLocation sunlit$registryName() {
        return this.registry == null ? null : this.registry.getRegistryName();
    }
}
