package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sunlit.bettersunlit.client.ParticleRainClientPerf;
import dev.sunlit.bettersunlit.client.WeatherParticleBridge;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import pigcart.particlerain.VersionUtil;
import pigcart.particlerain.WindManager;
import pigcart.particlerain.config.ConfigData;
import pigcart.particlerain.config.ConfigManager;
import pigcart.particlerain.config.ParticleData;
import pigcart.particlerain.particle.WeatherParticle;

@Pseudo
@Mixin(targets = "pigcart.particlerain.particle.CustomParticle", remap = false)
public abstract class PerfCustomParticleMixin extends WeatherParticle {
    @Shadow
    public ParticleData data;

    @Shadow
    private float oCollisionAnimProgress;

    @Shadow
    private float collisionAnimProgress;

    @Shadow
    public float speed;

    @Shadow
    float rotationVariation;

    @Shadow
    float baseTemp;

    @Shadow
    float distance;

    protected PerfCustomParticleMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z, null);
    }

    @Shadow
    public abstract void tickFading();

    @Shadow
    public abstract void onCollision(BlockHitResult result);

    @Shadow
    public abstract float getDistanceSize();

    @Shadow
    private void renderSquishyRotatedQuad(VertexConsumer vc, Quaternionf quat,
                                          float x, float y, float z, float partialTick, float yScale) {
        throw new AssertionError();
    }

    @Overwrite(remap = false)
    public void m_5989_() {
        super.m_5989_();
        WeatherParticleBridge bridge = (WeatherParticleBridge) (Object) this;
        bridge.sunlit$setOQuadSize(this.f_107663_);
        Vec3 camPos = VersionUtil.camPos(Minecraft.m_91087_().f_91063_.m_109153_());
        double dx = camPos.f_82479_ - this.f_107212_;
        double dy = camPos.f_82480_ - this.f_107213_;
        double dz = camPos.f_82481_ - this.f_107214_;
        this.distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if ((double) this.distance > (double) this.data.spawnPos.renderDistance() + 0.5d) {
            this.m_107274_();
        }
        if (!this.pos.equals(this.oPos)) {
            this.onPositionUpdate();
            this.oPos.m_122190_(this.pos);
        }
        boolean anim = bridge.sunlit$doCollisionAnim();
        if (anim) {
            this.tickCollisionAnim();
        }
        this.speed = (float) Math.sqrt(
                this.f_107215_ * this.f_107215_
                + this.f_107216_ * this.f_107216_
                + this.f_107217_ * this.f_107217_);
        this.tickFading();
        if (this.data.distanceScaling.floatValue() != 0.0f && !anim) {
            this.f_107663_ = this.getDistanceSize();
        }
        if (this.data.rotationAmount.floatValue() != 0.0f) {
            this.f_107204_ = this.f_107231_;
            this.f_107231_ += this.rotationVariation * this.speed;
        }
        if (this.data.animateSprites.booleanValue()) {
            int idx = (this.f_107224_ - 1) % (this.data.spriteLocations.size() - 1);
            String spriteLoc = this.data.spriteLocations.get(idx);
            this.m_108337_(VersionUtil.getSprite(VersionUtil.parseId(spriteLoc)));
        }
        float rainLevel = this.f_107208_.m_46722_(1.0f);
        if (this.data.tintType == ParticleData.TintType.FOG && rainLevel > 0.0f && rainLevel < 1.0f) {
            this.data.tintType.applyTint(this, this.f_107208_, this.pos, this.data);
        }
        WindManager.applyWind(this, this.data, this.f_107208_);
        this.tickCollisions();
    }

    @Overwrite(remap = false)
    public void onPositionUpdate() {
        ConfigData.CompatibilityOptions compat = ConfigManager.getConfig().compat;
        if (!compat.crossBiomeBorder) {
            Holder<Biome> biomeHolder = ParticleRainClientPerf.getBiomeFast(this.f_107208_, this.pos);
            if (biomeHolder.m_203334_().m_47554_() != this.baseTemp
                    && !this.data.precipitation.contains(
                            VersionUtil.getPrecipitationAt(this.f_107208_, biomeHolder, this.pos))) {
                ((WeatherParticleBridge) (Object) this).sunlit$setDoCollisionAnim(true);
                return;
            }
        }
        BlockState state = this.f_107208_.m_8055_(this.pos);
        if (!state.m_60795_()
                && state.m_60838_(this.f_107208_, this.pos)
                && (!compat.weatherIgnoreBlocks.contains(state.m_222976_()) || !state.m_60819_().m_76178_())) {
            this.m_107274_();
        }
    }

    @Overwrite(remap = false)
    public void tickCollisions() {
        if (((WeatherParticleBridge) (Object) this).sunlit$doCollisionAnim()) {
            return;
        }
        double vx = this.f_107215_;
        double vy = this.f_107216_;
        double vz = this.f_107217_;
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        float reach = (float) ((double) this.f_107663_ + len);
        double nx = len < 1.0E-4D ? 0.0D : vx / len;
        double ny = len < 1.0E-4D ? 0.0D : vy / len;
        double nz = len < 1.0E-4D ? 0.0D : vz / len;
        double sx = this.f_107212_;
        double sy = this.f_107213_;
        double sz = this.f_107214_;
        double ex = nx * (double) reach + sx;
        double ey = ny * (double) reach + sy;
        double ez = nz * (double) reach + sz;
        if (ParticleRainClientPerf.isClearAirRay(
                this.f_107208_,
                ParticleRainClientPerf.scratch().rayPos,
                sx, sy, sz,
                ex, ey, ez)) {
            return;
        }
        Vec3 start = new Vec3(sx, sy, sz);
        Vec3 end = new Vec3(ex, ey, ez);
        BlockHitResult hit = this.f_107208_.m_45547_(VersionUtil.getClipContext(start, end));
        if (hit.m_6662_() != HitResult.Type.MISS) {
            this.onCollision(hit);
        }
    }

    @Overwrite(remap = false)
    public void renderVerticalQuad(VertexConsumer vc, Camera camera, float partialTick) {
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
    }

    @Overwrite(remap = false)
    public void renderRelativeVelocityQuad(VertexConsumer vc, Camera camera, float partialTick) {
        Vec3 camVel = Minecraft.m_91087_().m_91288_().m_20184_();
        ParticleRainClientPerf.Scratch s = ParticleRainClientPerf.scratch();
        s.vVel.set(
                (float) (this.f_107215_ - camVel.f_82479_),
                (float) (this.f_107216_ - camVel.f_82480_),
                (float) (this.f_107217_ - camVel.f_82481_));
        this.renderVelocityQuad(vc, camera, partialTick, s.vVel);
    }

    @Overwrite(remap = false)
    public void renderWorldVelocityQuad(VertexConsumer vc, Camera camera, float partialTick) {
        ParticleRainClientPerf.Scratch s = ParticleRainClientPerf.scratch();
        s.vVel.set((float) this.f_107215_, (float) this.f_107216_, (float) this.f_107217_);
        this.renderVelocityQuad(vc, camera, partialTick, s.vVel);
    }

    @Overwrite(remap = false)
    public void renderVelocityQuad(VertexConsumer vc, Camera camera, float partialTick, Vector3f velocity) {
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
    }

    @Overwrite(remap = false)
    public void renderCameraCopyQuad(VertexConsumer vc, Camera camera, float partialTick) {
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
    }

    @Overwrite(remap = false)
    public void renderHorizontalQuad(VertexConsumer vc, Camera camera, float partialTick) {
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
    }

    @Overwrite(remap = false)
    private void renderVertex(VertexConsumer vc, Quaternionf quat,
                              float x, float y, float z,
                              float xOff, float yOff, float size,
                              float u, float v, int light) {
        ParticleRainClientPerf.emitVertex(
                vc, quat, x, y, z, xOff, yOff, size, u, v,
                this.f_107227_, this.f_107228_, this.f_107229_, this.f_107230_,
                light, ParticleRainClientPerf.scratch().vCorner);
    }
}
