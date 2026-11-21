package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.SprinklerEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Iterator;

@Pseudo
@Mixin(targets = "cool.bot.dewdropfarmland.utils.CropHandlerUtils", remap = false)
public abstract class DewDropSprinklerMixin {
    // Each successful return has the sprinkler position in scope. The last return is a miss.
    @Inject(method = "isNearSprinkler(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z",
            at = {@At(value = "RETURN", ordinal = 0), @At(value = "RETURN", ordinal = 1),
                  @At(value = "RETURN", ordinal = 2), @At(value = "RETURN", ordinal = 3)},
            locals = LocalCapture.CAPTURE_FAILHARD, require = 4)
    private static void sunlit$onSprinklerFound(LevelReader level, BlockPos farmland,
            CallbackInfoReturnable<Boolean> result, Iterator<BlockPos> positions, BlockPos sprinkler) {
        if (level instanceof ServerLevel serverLevel) {
            SprinklerEffects.watered(serverLevel, sprinkler, farmland);
        }
    }
}
