package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.SeasonPerf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "sereneseasons.season.SeasonHooks", remap = false)
public abstract class PerfSeasonHooksMixin {
    @Overwrite(remap = false)
    public static float getBiomeTemperature(Level level, Holder<Biome> biome, BlockPos pos) {
        return SeasonPerf.getBiomeTemperature(level, biome, pos);
    }
}
