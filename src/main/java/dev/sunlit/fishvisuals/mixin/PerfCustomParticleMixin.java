package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sunlit.bettersunlit.client.ParticleRainClientPerf;
import dev.sunlit.bettersunlit.client.WeatherParticleBridge;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pigcart.particlerain.VersionUtil;
import pigcart.particlerain.particle.WeatherParticle;

@Pseudo
@Mixin(targets = "pigcart.particlerain.particle.CustomParticle", remap = false)
public abstract class PerfCustomParticleMixin extends WeatherParticle {
    @Shadow
    private float oCollisionAnimProgress;

    @Shadow
    private float collisionAnimProgress;

    protected PerfCustomParticleMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z, null);
    }

    @Shadow
    public void renderVelocityQuad(VertexConsumer vc, Camera camera, float partialTick, Vector3f velocity) {
        throw new AssertionError();
    }

    @Shadow
    private void renderSquishyRotatedQuad(VertexConsumer vc, Quaternionf quat,
                                          float x, float y, float z, float partialTick, float yScale) {
        throw new AssertionError();
    }

    @Inject(method = "renderVerticalQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$renderVerticalQuadNoAlloc(VertexConsumer vc, Camera camera, float partialTick,
                                                  CallbackInfo ci) {
        Vec3 camPos = VersionUtil.camPos(camera);
        float x = (float) (Mth.m_14139_(partialTick, this.f_107209_, this.f_107212_) - camPos.m_7096_());
        float y = (float) (Mth.m_14139_(partialTick, this.f_107210_, this.f_107213_) - camPos.m_7098_());
        float z = (float) (Mth.m_14139_(partialTick, this.f_107211_, this.f_107214_) - camPos.m_7094_());
        ParticleRainClientPerf.Scratch s = ParticleRainClientPerf.scratch();
        s.q0.identity()
                .rotateY(org.joml.Math.atan2(x, z))
                .rotateZ(Mth.m_14179_(partialTick, this.f_107204_, this.f_107231_));
        s.v0.set(x, y, z);
        this.turnBackfaceFlipways(s.q0, s.v0);
        this.renderRotatedQuad(vc, s.q0, x, y, z, partialTick);
        ci.cancel();
    }

    @Inject(method = "renderRelativeVelocityQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$renderRelativeVelocityQuadNoAlloc(VertexConsumer vc, Camera camera, float partialTick,
                                                          CallbackInfo ci) {
        Vec3 camVel = Minecraft.m_91087_().m_91288_().m_20184_();
        ParticleRainClientPerf.Scratch s = ParticleRainClientPerf.scratch();
        s.vVel.set(
                (float) (this.f_107215_ - camVel.f_82479_),
                (float) (this.f_107216_ - camVel.f_82480_),
                (float) (this.f_107217_ - camVel.f_82481_));
        this.renderVelocityQuad(vc, camera, partialTick, s.vVel);
        ci.cancel();
    }

    @Inject(method = "renderWorldVelocityQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$renderWorldVelocityQuadNoAlloc(VertexConsumer vc, Camera camera, float partialTick,
                                                       CallbackInfo ci) {
        ParticleRainClientPerf.Scratch s = ParticleRainClientPerf.scratch();
        s.vVel.set((float) this.f_107215_, (float) this.f_107216_, (float) this.f_107217_);
        this.renderVelocityQuad(vc, camera, partialTick, s.vVel);
        ci.cancel();
    }

    @Inject(method = "renderVelocityQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;FLorg/joml/Vector3f;)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$renderVelocityQuadNoAlloc(VertexConsumer vc, Camera camera, float partialTick,
                                                  Vector3f velocity, CallbackInfo ci) {
        Vec3 camPos = VersionUtil.camPos(camera);
        float x = (float) (Mth.m_14139_(partialTick, this.f_107209_, this.f_107212_) - camPos.m_7096_());
        float y = (float) (Mth.m_14139_(partialTick, this.f_107210_, this.f_107213_) - camPos.m_7098_());
        float z = (float) (Mth.m_14139_(partialTick, this.f_107211_, this.f_107214_) - camPos.m_7094_());
        ParticleRainClientPerf.Scratch s = ParticleRainClientPerf.scratch();
        boolean anim = ((WeatherParticleBridge) (Object) this).sunlit$doCollisionAnim();
        float yScale = ParticleRainClientPerf.prepareVelocityQuaternion(
                velocity, x, y, z, anim, partialTick,
                this.oCollisionAnimProgress, this.collisionAnimProgress, s);
        this.renderSquishyRotatedQuad(vc, s.q0, x, y, z, partialTick, yScale);
        ci.cancel();
    }

    @Inject(method = "renderCameraCopyQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$renderCameraCopyQuadNoAlloc(VertexConsumer vc, Camera camera, float partialTick,
                                                    CallbackInfo ci) {
        Vec3 camPos = VersionUtil.camPos(camera);
        float x = (float) (Mth.m_14139_(partialTick, this.f_107209_, this.f_107212_) - camPos.m_7096_());
        float y = (float) (Mth.m_14139_(partialTick, this.f_107210_, this.f_107213_) - camPos.m_7098_());
        float z = (float) (Mth.m_14139_(partialTick, this.f_107211_, this.f_107214_) - camPos.m_7094_());
        ParticleRainClientPerf.Scratch s = ParticleRainClientPerf.scratch();
        s.q0.set(camera.m_253121_());
        if (this.f_107231_ != 0.0f) {
            s.q0.rotateZ(Mth.m_14179_(partialTick, this.f_107204_, this.f_107231_));
        }
        s.q0.mul(s.qAux.rotationY(3.1415927f));
        this.renderRotatedQuad(vc, s.q0, x, y, z, partialTick);
        ci.cancel();
    }

    @Inject(method = "renderHorizontalQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$renderHorizontalQuadNoAlloc(VertexConsumer vc, Camera camera, float partialTick,
                                                    CallbackInfo ci) {
        Vec3 camPos = VersionUtil.camPos(camera);
        float x = (float) (Mth.m_14139_(partialTick, this.f_107209_, this.f_107212_) - camPos.m_7096_());
        float y = (float) (Mth.m_14139_(partialTick, this.f_107210_, this.f_107213_) - camPos.m_7098_());
        float z = (float) (Mth.m_14139_(partialTick, this.f_107211_, this.f_107214_) - camPos.m_7094_());
        ParticleRainClientPerf.Scratch s = ParticleRainClientPerf.scratch();
        s.q0.set(s.axis4d.set(1.5707963705062866d, -1.0d, 0.0d, 0.0d));
        if (this.f_107231_ != 0.0f) {
            s.q0.rotateZ(Mth.m_14179_(partialTick, this.f_107204_, this.f_107231_));
        }
        s.v0.set(x, y, z);
        this.turnBackfaceFlipways(s.q0, s.v0);
        this.renderRotatedQuad(vc, s.q0, x, y, z, partialTick);
        ci.cancel();
    }

    @Inject(method = "renderVertex(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Quaternionf;FFFFFFFFI)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$renderVertexNoAlloc(VertexConsumer vc, Quaternionf quat,
                                            float x, float y, float z,
                                            float xOff, float yOff, float size,
                                            float u, float v, int light,
                                            CallbackInfo ci) {
        ParticleRainClientPerf.emitVertex(
                vc, quat, x, y, z, xOff, yOff, size, u, v,
                this.f_107227_, this.f_107228_, this.f_107229_, this.f_107230_,
                light, ParticleRainClientPerf.scratch().vCorner);
        ci.cancel();
    }
}
