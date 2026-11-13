package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.SeasonPerf;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.season.SeasonHandler;

@Pseudo
@Mixin(targets = "sereneseasons.season.SeasonHandler", remap = false)
public abstract class PerfSeasonHandlerMixin {
    @Inject(method = "getServerSeasonState(Lnet/minecraft/world/level/Level;)Lsereneseasons/api/season/ISeasonState;",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$cachedServerSeasonState(Level level, CallbackInfoReturnable<ISeasonState> cir) {
        cir.setReturnValue(SeasonPerf.seasonState(SeasonHandler.getSeasonSavedData(level).seasonCycleTicks));
    }
}
