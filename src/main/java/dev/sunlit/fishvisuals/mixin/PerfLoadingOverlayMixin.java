package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.StartupLoadPerf;
import dev.sunlit.bettersunlit.client.StartupOverlayAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the 2-second cosmetic fadeout once {@link LoadingOverlay} finishes reload completion and
 * {@code Screen.init} has run, and exposes reload readiness to {@link StartupLoadPerf}.
 */
@Mixin(value = LoadingOverlay.class, priority = 2000, remap = false)
public abstract class PerfLoadingOverlayMixin implements StartupOverlayAccess {
    @Shadow
    @Final
    private Minecraft f_96163_;

    @Shadow
    @Final
    private ReloadInstance f_96164_;

    @Shadow
    private long f_96168_;

    @Override
    public boolean sunlit$isReloadDone() {
        return this.f_96164_ != null && this.f_96164_.m_7746_();
    }

    @Inject(method = "m_88315_", at = @At("RETURN"))
    private void sunlit$finishOverlayAfterReload(
            GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (this.f_96168_ > -1L && this.f_96163_ != null && this.f_96163_.m_91265_() == (Object) this) {
            this.f_96168_ = 0L;
            this.f_96163_.m_91150_(null);
            StartupLoadPerf.syncVsync(this.f_96163_);
        }
    }
}
