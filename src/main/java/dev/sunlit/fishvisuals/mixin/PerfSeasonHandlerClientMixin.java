package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.SeasonPerf;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.season.SeasonHandlerClient;

@Pseudo
@Mixin(targets = "sereneseasons.season.SeasonHandler", remap = false)
public abstract class PerfSeasonHandlerClientMixin {
    @Inject(method = "getClientSeasonState(Lnet/minecraft/world/level/Level;)Lsereneseasons/api/season/ISeasonState;",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$cachedClientSeasonState(Level level, CallbackInfoReturnable<ISeasonState> cir) {
        Integer boxed = level != null ? SeasonHandlerClient.clientSeasonCycleTicks.get(level.m_46472_()) : null;
        cir.setReturnValue(SeasonPerf.seasonState(boxed != null ? boxed.intValue() : 0));
    }
}
