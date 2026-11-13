package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.SeasonPerf;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.season.SeasonHandlerClient;

@Pseudo
@Mixin(targets = "sereneseasons.season.SeasonHandler", remap = false)
public abstract class PerfSeasonHandlerClientMixin {
    @Overwrite(remap = false)
    public ISeasonState getClientSeasonState(Level level) {
        Integer boxed = level != null ? SeasonHandlerClient.clientSeasonCycleTicks.get(level.m_46472_()) : null;
        return SeasonPerf.seasonState(boxed != null ? boxed.intValue() : 0);
    }
}
