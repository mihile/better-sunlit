package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ParticleRainClientPerf;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips {@link LevelRenderer#m_109703_} after dimension weather effects and Iris phase setup when
 * ParticleRain disables both vanilla rain and vanilla snow rendering, preventing hundreds of wasted
 * per-column {@code BiomeManager.m_204214_} lookups every rainy frame.
 */
@Mixin(value = LevelRenderer.class, remap = false)
public abstract class PerfLevelRendererWeatherMixin {
    @Inject(
            method = "m_109703_(Lnet/minecraft/client/renderer/LightTexture;FDDD)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LightTexture;m_109896_()V",
                    remap = false),
            cancellable = true,
            require = 1)
    private void sunlit$skipNullifiedWeatherLoop(LightTexture lightTexture, float partialTick,
                                                 double camX, double camY, double camZ,
                                                 CallbackInfo ci) {
        if (ParticleRainClientPerf.shouldSkipVanillaWeatherLoop()) {
            ci.cancel();
        }
    }
}
