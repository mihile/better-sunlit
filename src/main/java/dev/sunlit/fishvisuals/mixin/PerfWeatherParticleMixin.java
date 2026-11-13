package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sunlit.bettersunlit.client.ParticleRainClientPerf;
import dev.sunlit.bettersunlit.client.WeatherParticleBridge;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "pigcart.particlerain.particle.WeatherParticle", remap = false)
public abstract class PerfWeatherParticleMixin extends TextureSheetParticle implements WeatherParticleBridge {
    @Shadow
    protected BlockPos.MutableBlockPos pos;

    @Shadow
    boolean doCollisionAnim;

    @Shadow
    float oQuadSize;

    protected PerfWeatherParticleMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Override
    public boolean sunlit$doCollisionAnim() {
        return this.doCollisionAnim;
    }

    @Override
    public void sunlit$setOQuadSize(float value) {
        this.oQuadSize = value;
    }

    @Override
    protected int m_6355_(float partialTick) {
        BlockPos.MutableBlockPos p = this.pos;
        p.m_122169_(this.f_107212_, this.f_107213_, this.f_107214_);
        return this.f_107208_.m_46805_(p) ? LevelRenderer.m_109541_(this.f_107208_, p) : 0;
    }

    @Overwrite(remap = false)
    public Quaternionf turnBackfaceFlipways(Quaternionf quat, Vector3f posToCam) {
        return ParticleRainClientPerf.turnBackfaceFlipways(
                quat, posToCam, ParticleRainClientPerf.scratch());
    }

    @Overwrite(remap = false)
    protected void renderRotatedQuad(VertexConsumer vc, Quaternionf quat,
                                     float x, float y, float z, float partialTick) {
        ParticleRainClientPerf.emitRotatedQuad(
                vc, quat, x, y, z, this.m_5902_(partialTick),
                this.m_5970_(), this.m_5952_(), this.m_5951_(), this.m_5950_(),
                this.f_107227_, this.f_107228_, this.f_107229_, this.f_107230_,
                this.m_6355_(partialTick), ParticleRainClientPerf.scratch().vCorner);
    }
}
