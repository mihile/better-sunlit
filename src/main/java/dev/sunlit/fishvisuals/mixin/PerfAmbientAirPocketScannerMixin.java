package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.AmbientSoundsClientPerf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import team.creative.ambientsounds.environment.pocket.AirPocketScanner;

@Pseudo
@Mixin(targets = "team.creative.ambientsounds.environment.pocket.AirPocketScanner", remap = false)
public abstract class PerfAmbientAirPocketScannerMixin {
    @Shadow
    private BlockPos.MutableBlockPos mutable;

    @Redirect(
            method = "scan(Lnet/minecraft/world/level/Level;ILteam/creative/ambientsounds/environment/pocket/AirPocketScanner$BlockPosInspection;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"),
            require = 2,
            remap = false
    )
    private Direction[] sunlit$cachedDirections() {
        return AmbientSoundsClientPerf.DIRECTIONS;
    }

    @Redirect(
            method = "scan(Lnet/minecraft/world/level/Level;ILteam/creative/ambientsounds/environment/pocket/AirPocketScanner$BlockPosInspection;)V",
            at = @At(value = "INVOKE", target = "Lteam/creative/ambientsounds/environment/pocket/AirPocketScanner$BlockPosInspection;m_121945_(Lnet/minecraft/core/Direction;)Lnet/minecraft/core/BlockPos;"),
            require = 2,
            remap = false
    )
    private BlockPos sunlit$reuseMutableRelative(AirPocketScanner.BlockPosInspection self, Direction dir) {
        return this.mutable.m_122178_(
                self.m_123341_() + dir.m_122429_(),
                self.m_123342_() + dir.m_122430_(),
                self.m_123343_() + dir.m_122431_());
    }
}
