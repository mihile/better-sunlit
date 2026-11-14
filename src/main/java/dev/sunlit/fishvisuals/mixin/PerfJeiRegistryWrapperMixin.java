package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.JeiRegistryPerf;
import mezz.jei.common.platform.IPlatformRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Caches JEI's {@link IPlatformRegistry} wrapper per {@link ResourceKey} while validating active
 * {@code ForgeRegistry} identity.
 */
@Pseudo
@Mixin(targets = "mezz.jei.forge.platform.RegistryWrapper", priority = 2000, remap = false)
public abstract class PerfJeiRegistryWrapperMixin {

    /**
     * @author Better Sunlit
     * @reason Reuse cached IPlatformRegistry wrapper per ResourceKey across JEI init/lookups
     */
    @Overwrite
    public static <T, V> IPlatformRegistry<T> getRegistry(ResourceKey<? extends Registry<T>> key) {
        return JeiRegistryPerf.getRegistry(key);
    }
}
