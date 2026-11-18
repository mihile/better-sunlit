package dev.sunlit.fishvisuals.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(targets = "journeymap.client.ui.minimap.MiniMap", remap = false)
public abstract class PerfJourneyMapResetMixin {
    // reset() has just rebuilt this grid. Keep the display-variable refresh,
    // including its forced update, but omit the second identical grid rebuild.
    @ModifyArg(method = "reset", index = 5, at = @At(value = "INVOKE",
            target = "Ljourneymap/client/ui/minimap/MiniMap;updateDisplayVars(Ljourneymap/client/ui/minimap/Shape;FFLjourneymap/client/ui/minimap/Position;ZZ)V"))
    private boolean sunlit$reuseResetGrid(boolean rebuild) {
        return false;
    }
}
