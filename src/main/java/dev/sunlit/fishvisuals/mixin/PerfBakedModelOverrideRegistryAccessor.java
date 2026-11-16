package dev.sunlit.fishvisuals.mixin;

import com.refinedmods.refinedstorage.render.BakedModelOverrideRegistry;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(value = BakedModelOverrideRegistry.class, remap = false)
public interface PerfBakedModelOverrideRegistryAccessor {
    @Accessor("registry")
    Map<ResourceLocation, BakedModelOverrideRegistry.BakedModelOverrideFactory> sunlit$getRegistry();
}
