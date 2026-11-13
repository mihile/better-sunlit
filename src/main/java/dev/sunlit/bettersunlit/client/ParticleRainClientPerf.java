package dev.sunlit.bettersunlit.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.joml.AxisAngle4d;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import pigcart.particlerain.ParticleRain;
import pigcart.particlerain.config.ConfigData;
import pigcart.particlerain.config.ConfigManager;
import pigcart.particlerain.config.ParticleData;

import java.util.ArrayList;

/**
 * Thread-local scratch state and allocation-free geometry/wind math for ParticleRain
 * quad rendering, wind gusts, and surface/sky/block particle spawning.
 */
public final class ParticleRainClientPerf {
    private static final float PI_F = 3.1415927f;

    public static final class Scratch {
        public final Quaternionf q0 = new Quaternionf();
        public final Quaternionf qAux = new Quaternionf();
        public final AxisAngle4f axis4f = new AxisAngle4f();
        public final AxisAngle4d axis4d = new AxisAngle4d();
        public final Vector3f v0 = new Vector3f();
        public final Vector3f v1 = new Vector3f();
        public final Vector3f v2 = new Vector3f();
        public final Vector3f v3 = new Vector3f();
        public final Vector3f vVel = new Vector3f();
        public final Vector3f vCorner = new Vector3f();
        public final Vector3f vAux = new Vector3f();
        public final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        public final ArrayList<ParticleData> candidates = new ArrayList<>(16);
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private ParticleRainClientPerf() {}

    public static Scratch scratch() {
        return SCRATCH.get();
    }

    public static float velocityAngle(Vector3f velocity, Vector3f scratch) {
        scratch.set(velocity);
        float lenSq = scratch.lengthSquared();
        if (Float.isFinite(lenSq) && lenSq > 1.0E-20f) {
            scratch.normalize();
        } else {
            scratch.set(0.0f, -1.0f, 0.0f);
        }
        return org.joml.Math.acos(Math.max(-1.0f, Math.min(1.0f, scratch.y)));
    }

    public static Vector3f velocityAxis(Vector3f velocity, Vector3f scratch) {
        scratch.set(-velocity.z(), 0.0f, velocity.x());
        float lenSq = scratch.lengthSquared();
        if (Float.isFinite(lenSq) && lenSq > 1.0E-20f) {
            scratch.normalize();
        } else {
            scratch.set(1.0f, 0.0f, 0.0f);
        }
        return scratch;
    }

    public static Quaternionf turnBackfaceFlipways(Quaternionf quat, Vector3f posToCam, Scratch s) {
        s.vAux.set(0.0f, 0.0f, 1.0f).rotate(quat).normalize();
        if (s.vAux.dot(posToCam) > 0.0f) {
            quat.mul(s.qAux.rotationY(PI_F));
        }
        return quat;
    }

    /** Configures {@code s.q0} for {@link pigcart.particlerain.particle.CustomParticle#renderVelocityQuad} and returns vertical squish. */
    public static float prepareVelocityQuaternion(Vector3f velocity, float x, float y, float z,
                                                  boolean doCollisionAnim, float partialTick,
                                                  float oCollisionAnimProgress, float collisionAnimProgress,
                                                  Scratch s) {
        float angle = velocityAngle(velocity, s.v1);
        Vector3f axis = velocityAxis(velocity, s.v2);
        s.q0.set(s.axis4f.set(-angle, axis));
        s.v3.set(x, y, z).rotateAxis(angle, axis.x, axis.y, axis.z);
        s.q0.mul(s.qAux.rotationY(org.joml.Math.atan2(s.v3.x, s.v3.z) + PI_F));
        float yScale = Mth.m_14036_(velocity.lengthSquared(), 0.25f, 1.0f);
        if (doCollisionAnim) {
            float progress = Mth.m_14179_(partialTick, oCollisionAnimProgress, collisionAnimProgress);
            if (progress < yScale) yScale = progress;
        }
        return yScale;
    }

    public static void emitVertex(VertexConsumer vc, Quaternionf quat,
                                  float x, float y, float z,
                                  float xOff, float yOff, float size,
                                  float u, float v,
                                  float r, float g, float b, float a,
                                  int light, Vector3f cornerScratch) {
        cornerScratch.set(xOff, yOff, 0.0f).rotate(quat).mul(size).add(x, y, z);
        vc.m_5483_(cornerScratch.x(), cornerScratch.y(), cornerScratch.z())
                .m_7421_(u, v)
                .m_85950_(r, g, b, a)
                .m_85969_(light)
                .m_5752_();
    }

    public static void emitRotatedQuad(VertexConsumer vc, Quaternionf quat,
                                       float x, float y, float z, float size,
                                       float u0, float u1, float v0, float v1,
                                       float r, float g, float b, float a,
                                       int light, Vector3f cornerScratch) {
        quat.rotateY(PI_F);
        emitVertex(vc, quat, x, y, z, -1.0f, -1.0f, size, u1, v1, r, g, b, a, light, cornerScratch);
        emitVertex(vc, quat, x, y, z, -1.0f,  1.0f, size, u1, v0, r, g, b, a, light, cornerScratch);
        emitVertex(vc, quat, x, y, z,  1.0f,  1.0f, size, u0, v0, r, g, b, a, light, cornerScratch);
        emitVertex(vc, quat, x, y, z,  1.0f, -1.0f, size, u0, v1, r, g, b, a, light, cornerScratch);
    }

    public static float computeOneWind(float coord, float gustFrequency, float modulatedTime, float strength) {
        float primary = Mth.m_14031_(coord * gustFrequency + modulatedTime);
        float half = Mth.m_14031_(coord * gustFrequency * 0.5f + modulatedTime * 0.5f);
        return -(primary * half * strength + strength);
    }

    public static Vector3f fillWind(double x, double y, double z, Vector3f out) {
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (level == null) {
            return out.set(0.0f, 0.0f, 0.0f);
        }
        ConfigData.WindOptions wind = ConfigManager.getConfig().wind;
        float mod = (float) level.m_46467_() * wind.modulationSpeed;
        float yMul = wind.yLevelAdjustment ? ParticleRain.yLevelWindMultiplier(y) : 0.0f;
        float wx = computeOneWind((float) x, wind.gustFrequency, mod, wind.strength) * yMul;
        float wz = computeOneWind((float) z, wind.gustFrequency, mod, wind.strength) * yMul;
        return out.set(wx, 0.0f, wz);
    }
}
