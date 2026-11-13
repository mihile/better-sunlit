package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.SeasonPerf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Pseudo
@Mixin(targets = "sereneseasons.config.SeasonsConfig", remap = false)
public abstract class PerfSeasonsConfigMixin {
    @Shadow
    public List<String> whitelistedDimensions;

    @Overwrite(remap = false)
    public boolean isDimensionWhitelisted(ResourceKey<Level> dimension) {
        return SeasonPerf.isDimensionWhitelisted(this.whitelistedDimensions, dimension);
    }
}
