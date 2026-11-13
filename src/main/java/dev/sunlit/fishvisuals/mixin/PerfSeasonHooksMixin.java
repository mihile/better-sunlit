package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.SeasonPerf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "sereneseasons.season.SeasonHooks", remap = false)
public abstract class PerfSeasonHooksMixin {
    @Inject(method = "getBiomeTemperature(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;)F",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private static void sunlit$getBiomeTemperatureFast(Level level, Holder<Biome> biome, BlockPos pos,
                                                        CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(SeasonPerf.getBiomeTemperature(level, biome, pos));
    }
}
