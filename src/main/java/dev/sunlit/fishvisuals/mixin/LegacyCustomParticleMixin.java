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
import pigcart.particlerain.config.ConfigData;
import pigcart.particlerain.config.ConfigManager;
import pigcart.particlerain.config.ParticleData;
import pigcart.particlerain.particle.WeatherParticle;
@Pseudo
@Mixin(targets="pigcart.particlerain.particle.CustomParticle",remap=false)
public abstract class LegacyCustomParticleMixin extends WeatherParticle {
 @Shadow private float oCollisionAnimProgress;
 @Shadow private float collisionAnimProgress;
 @Shadow private void renderSquishyRotatedQuad(VertexConsumer vc, Quaternionf q,float x,float y,float z,float p,float scale){throw new AssertionError();}
 protected LegacyCustomParticleMixin(ClientLevel l,double x,double y,double z){super(l,x,y,z,null);}
 @Shadow boolean doCollisionAnim;
 @Shadow public ParticleData opts;
 @Shadow public net.minecraft.core.BlockPos.MutableBlockPos pos;
 @Shadow protected net.minecraft.core.BlockPos.MutableBlockPos oPos;
 @Shadow float oQuadSize;
 @Shadow float distance;
 @Shadow private float speed;
 @Shadow @org.spongepowered.asm.mixin.Final private float rotationVariation;
 @Shadow public abstract void onPositionUpdate();
 @Shadow public abstract void tickCollisionAnim();
 @Shadow public abstract void tickDistanceFade();
 @Shadow public abstract void tickWind();
 @Shadow public abstract void tickCollisions();
 @Shadow public abstract float getDistanceSize();
 @Overwrite(remap=false)
 public void m_5989_(){
  super.m_5989_();this.oQuadSize=this.f_107663_;
  Vec3 camera=VersionUtil.camPos(Minecraft.m_91087_().f_91063_.m_109153_());
  double dx=camera.f_82479_-this.f_107212_,dy=camera.f_82480_-this.f_107213_,dz=camera.f_82481_-this.f_107214_;
  this.distance=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
  this.pos.m_122169_(this.f_107212_,this.f_107213_,this.f_107214_);
  if(!this.pos.equals(this.oPos)){this.onPositionUpdate();this.oPos.m_122190_(this.pos);}
  if(this.doCollisionAnim)this.tickCollisionAnim();
  this.speed=(float)Math.sqrt(this.f_107215_*this.f_107215_+this.f_107216_*this.f_107216_+this.f_107217_*this.f_107217_);
  if(dev.sunlit.bettersunlit.client.LegacyParticleSettings.constantScreenSize(this.opts)&&!this.doCollisionAnim)this.f_107663_=this.getDistanceSize();
  if(this.opts.rotationAmount.floatValue()!=0f){this.f_107204_=this.f_107231_;this.f_107231_+=this.rotationVariation*this.speed;}
  this.tickDistanceFade();this.tickWind();this.tickCollisions();
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
    @org.spongepowered.asm.mixin.Unique
    public void renderVelocityQuad(VertexConsumer vc, Camera camera, float partialTick, Vector3f velocity) {
        Vec3 camPos = VersionUtil.camPos(camera);
        float x = (float) (Mth.m_14139_(partialTick, this.f_107209_, this.f_107212_) - camPos.m_7096_());
        float y = (float) (Mth.m_14139_(partialTick, this.f_107210_, this.f_107213_) - camPos.m_7098_());
        float z = (float) (Mth.m_14139_(partialTick, this.f_107211_, this.f_107214_) - camPos.m_7094_());
        ParticleRainClientPerf.Scratch s = ParticleRainClientPerf.scratch();
        boolean anim = this.doCollisionAnim;
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
