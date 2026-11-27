package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.StartupLoadPerf;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Minecraft.class, remap = false)
public abstract class PerfMinecraftStartupTickMixin {
    @Redirect(
            method = "m_91383_(Z)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"))
    private int sunlit$capJoinCatchupTicks(int vanillaMax, int elapsedTicks) {
        return StartupLoadPerf.clampJoinCatchupTicks((Minecraft) (Object) this, vanillaMax, elapsedTicks);
    }
    @Inject(method = "m_91275_", at = @At("HEAD"), cancellable = true)
    private void sunlit$uncapOverlayFpsLimit(CallbackInfoReturnable<Integer> cir) {
        Minecraft self = (Minecraft) (Object) this;
        if (StartupLoadPerf.isStartupLoading(self)) {
            cir.setReturnValue(260);
        }
    }

    @Inject(method = "m_91383_", at = @At("HEAD"))
    private void sunlit$prepareStartupLoadTick(boolean renderLevel, CallbackInfo ci) {
        StartupLoadPerf.onRenderTickBegin((Minecraft) (Object) this);
    }

    @Inject(
            method = "m_91383_",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MouseHandler;m_91523_()V",
                    shift = At.Shift.BEFORE),
            cancellable = true)
    private void sunlit$deferStartupOverlayVisualFrame(boolean renderLevel, CallbackInfo ci) {
        Minecraft self = (Minecraft) (Object) this;
        if (StartupLoadPerf.shouldDeferVisualFrame(self)) {
            StartupLoadPerf.whileDeferringVisualFrame(self);
            ci.cancel();
        }
    }
}
