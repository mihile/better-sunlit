package dev.sunlit.fishvisuals.mixin;

import dev.ftb.mods.ftbchunks.client.FTBChunksClientConfig;
import dev.ftb.mods.ftbchunks.client.map.MapMode;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbchunks.client.FTBChunksClient", remap = false)
public abstract class PerfFtbChunksHiddenMinimapMixin {
    @Inject(method = "renderHud(Lnet/minecraft/client/gui/GuiGraphics;F)V",
            at = @At(value = "INVOKE",
                    target = "Ldev/ftb/mods/ftbchunks/client/FTBChunksClient;getZoom()F"),
            cancellable = true, require = 1)
    private void sunlit$skipUnusedMinimapTiles(GuiGraphics graphics, float delta, CallbackInfo callback) {
        // The verified target has already handled dimension changes, saves and texture creation here.
        // Keep the pending tile update so that reenabling the minimap refreshes it normally.
        if (!FTBChunksClientConfig.MINIMAP_ENABLED.get() && FTBChunksClientConfig.MAP_MODE.get() == MapMode.NONE) {
            callback.cancel();
        }
    }
}
