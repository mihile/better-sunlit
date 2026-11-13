package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sunlit.bettersunlit.client.ParticleRainClientPerf;
import dev.sunlit.bettersunlit.client.WeatherParticleBridge;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TextureSheetParticle;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "pigcart.particlerain.particle.WeatherParticle", remap = false)
public abstract class PerfWeatherParticleMixin extends TextureSheetParticle implements WeatherParticleBridge {
    @Shadow
    boolean doCollisionAnim;

    protected PerfWeatherParticleMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Override
    public boolean sunlit$doCollisionAnim() {
        return this.doCollisionAnim;
    }

    @Inject(method = "turnBackfaceFlipways(Lorg/joml/Quaternionf;Lorg/joml/Vector3f;)Lorg/joml/Quaternionf;",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$turnBackfaceNoAlloc(Quaternionf quat, Vector3f posToCam,
                                            CallbackInfoReturnable<Quaternionf> cir) {
        cir.setReturnValue(ParticleRainClientPerf.turnBackfaceFlipways(
                quat, posToCam, ParticleRainClientPerf.scratch()));
    }

    @Inject(method = "renderRotatedQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Quaternionf;FFFF)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$renderRotatedQuadNoAlloc(VertexConsumer vc, Quaternionf quat,
                                                 float x, float y, float z, float partialTick,
                                                 CallbackInfo ci) {
        ParticleRainClientPerf.emitRotatedQuad(
                vc, quat, x, y, z, this.m_5902_(partialTick),
                this.m_5970_(), this.m_5952_(), this.m_5951_(), this.m_5950_(),
                this.f_107227_, this.f_107228_, this.f_107229_, this.f_107230_,
                this.m_6355_(partialTick), ParticleRainClientPerf.scratch().vCorner);
        ci.cancel();
    }
}
