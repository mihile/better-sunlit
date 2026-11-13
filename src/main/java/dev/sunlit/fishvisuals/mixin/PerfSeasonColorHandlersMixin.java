package dev.sunlit.fishvisuals.mixin;

import com.google.common.collect.Multimap;
import dev.sunlit.bettersunlit.client.SeasonColorPerf;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sereneseasons.season.SeasonColorHandlers;

@Pseudo
@Mixin(targets = "sereneseasons.season.SeasonColorHandlers", remap = false)
public abstract class PerfSeasonColorHandlersMixin {
    @Shadow
    @Final
    private static Multimap<SeasonColorHandlers.ResolverType, SeasonColorHandlers.ColorOverride> resolverOverrides;

    @Shadow
    private static ColorResolver originalGrassColorResolver;

    @Shadow
    private static ColorResolver originalFoliageColorResolver;

    @Inject(method = "registerResolverOverride(Lsereneseasons/season/SeasonColorHandlers$ResolverType;Lsereneseasons/season/SeasonColorHandlers$ColorOverride;)V",
            at = @At("TAIL"), require = 1, remap = false)
    private static void sunlit$invalidateOverrideSnapshot(SeasonColorHandlers.ResolverType type,
                                                          SeasonColorHandlers.ColorOverride override,
                                                          CallbackInfo ci) {
        SeasonColorPerf.onOverrideRegistered();
    }

    @Overwrite(remap = false)
    private static int resolveColors(SeasonColorHandlers.ResolverType type, Biome biome, double x, double z) {
        return SeasonColorPerf.resolveColors(
                type, biome, x, z,
                originalGrassColorResolver, originalFoliageColorResolver, resolverOverrides);
    }
}
