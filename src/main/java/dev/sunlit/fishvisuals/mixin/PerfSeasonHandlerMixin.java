package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.SeasonPerf;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.season.SeasonHandler;

@Pseudo
@Mixin(targets = "sereneseasons.season.SeasonHandler", remap = false)
public abstract class PerfSeasonHandlerMixin {
    @Overwrite(remap = false)
    public ISeasonState getServerSeasonState(Level level) {
        return SeasonPerf.seasonState(SeasonHandler.getSeasonSavedData(level).seasonCycleTicks);
    }
}
