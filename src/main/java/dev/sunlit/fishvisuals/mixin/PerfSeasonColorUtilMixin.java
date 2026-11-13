package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.SeasonColorPerf;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import sereneseasons.api.season.ISeasonColorProvider;

@Pseudo
@Mixin(targets = "sereneseasons.util.SeasonColorUtil", remap = false)
public abstract class PerfSeasonColorUtilMixin {
    @Overwrite(remap = false)
    public static int saturateColour(int colour, float saturationMultiplier) {
        return SeasonColorPerf.saturateColour(colour, saturationMultiplier);
    }

    @Overwrite(remap = false)
    public static int applySeasonalGrassColouring(ISeasonColorProvider provider, Holder<Biome> biome, int originalColour) {
        return SeasonColorPerf.applySeasonalGrassColouring(provider, biome, originalColour);
    }

    @Overwrite(remap = false)
    public static int applySeasonalFoliageColouring(ISeasonColorProvider provider, Holder<Biome> biome, int originalColour) {
        return SeasonColorPerf.applySeasonalFoliageColouring(provider, biome, originalColour);
    }
}
