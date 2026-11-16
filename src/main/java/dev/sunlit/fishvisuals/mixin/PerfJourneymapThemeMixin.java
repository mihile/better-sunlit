package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.JourneymapThemePerf;
import journeymap.client.io.ThemeLoader;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

/**
 * Skips redundant JourneyMap built-in theme extraction from zip archives when already extracted.
 */
@Mixin(value = ThemeLoader.class, remap = false)
public abstract class PerfJourneymapThemeMixin {
    @Inject(method = "initialize(Z)V", at = @At("HEAD"))
    private static void sunlit$beginThemeInit(boolean forceReload, CallbackInfo ci) {
        JourneymapThemePerf.beginInitialize(forceReload);
    }

    @Redirect(
            method = "initialize(Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljourneymap/client/io/FileHandler;copyResources(Ljava/io/File;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;Z)Z"))
    private static boolean sunlit$skipRedundantThemeCopy(
            File targetDir,
            ResourceLocation location,
            String subDir,
            boolean overwrite) {
        return JourneymapThemePerf.copyPresetResources(targetDir, location, subDir, overwrite);
    }

    @Inject(method = "initialize(Z)V", at = @At("RETURN"))
    private static void sunlit$endThemeInit(boolean forceReload, CallbackInfo ci) {
        JourneymapThemePerf.endInitialize();
    }
}
