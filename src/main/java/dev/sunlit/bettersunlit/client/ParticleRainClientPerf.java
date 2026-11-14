package dev.sunlit.bettersunlit.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import org.joml.AxisAngle4d;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import pigcart.particlerain.ParticleLoader;
import pigcart.particlerain.ParticleRain;
import pigcart.particlerain.config.ConfigData;
import pigcart.particlerain.config.ConfigManager;
import pigcart.particlerain.config.ParticleData;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
        public final BlockPos.MutableBlockPos rayPos = new BlockPos.MutableBlockPos();
        public final ArrayList<ParticleData> candidates = new ArrayList<>(16);
        public final ArrayList<ParticleData> blockFxCandidates = new ArrayList<>(8);
        public Object cachedBlockFxLevel;
        public long cachedBlockFxTick = Long.MIN_VALUE;
        public Map<String, ParticleData> cachedBlockFxMap;
        public int cachedBlockFxMapSize = -1;
        public int cachedBlockFxEpoch = -1;
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

    /**
     * Returns {@code true} when ParticleRain suppresses both vanilla rain and vanilla snow rendering,
     * allowing {@code LevelRenderer.m_109703_} to skip its column biome loop once dimension effects
     * and Iris phase setup have executed.
     */
    public static boolean shouldSkipVanillaWeatherLoop() {
        return shouldSkipVanillaWeatherLoop(ConfigManager.getConfig());
    }

    public static boolean shouldSkipVanillaWeatherLoop(ConfigData config) {
        if (config == null || config.compat == null) {
            return false;
        }
        return shouldSkipVanillaWeatherLoop(config.compat.renderDefaultRain, config.compat.renderDefaultSnow);
    }

    public static boolean shouldSkipVanillaWeatherLoop(boolean renderDefaultRain, boolean renderDefaultSnow) {
        return !renderDefaultRain && !renderDefaultSnow;
    }

    /** Bridge implemented by {@code SingleValuePalette} mixin. */
    public interface SingleValuePaletteBridge {
        Object sunlit$paletteSingleValue();
    }

    /** Bridge implemented by {@code PalettedContainer} mixin + postApply patch. */
    public interface PalettedContainerBridge {
        Object sunlit$containerSingleValue();
    }

    private static volatile int whitelistEpoch;
    private static final AtomicBoolean TAG_HOOK_REGISTERED = new AtomicBoolean();

    private static final class ForgeTagHook {
        private ForgeTagHook() {
        }

        static void register() {
            MinecraftForge.EVENT_BUS.addListener(
                    EventPriority.NORMAL,
                    false,
                    TagsUpdatedEvent.class,
                    event -> invalidateWhitelistCaches());
        }
    }

    public static void ensureTagHookRegistered() {
        if (TAG_HOOK_REGISTERED.compareAndSet(false, true)) {
            try {
                ForgeTagHook.register();
            } catch (Throwable ignored) {
                // Headless unit test environments may lack an initialized Forge event bus
            }
        }
    }

    public static int whitelistCacheEpoch() {
        return whitelistEpoch;
    }

    public static void invalidateWhitelistCaches() {
        whitelistEpoch++;
    }

    /**
     * Returns the {@link Holder} biome for {@code pos}, bypassing {@code BiomeManager}'s Voronoi
     * 8-corner sampling whenever all candidate quart cells belong to single-biome chunk sections
     * containing the identical biome reference.
     */
    public static Holder<Biome> getBiomeFast(ClientLevel level, BlockPos pos) {
        ChunkSource source = level.m_7726_();
        if (!(source instanceof ClientChunkCache chunkCache)) {
            return level.m_204166_(pos);
        }
        int x = pos.m_123341_();
        int y = pos.m_123342_();
        int z = pos.m_123343_();
        int secIdx = level.m_151564_(y);
        int cx = x >> 4;
        int cz = z >> 4;
        LevelChunk centerChunk = chunkCache.m_7587_(cx, cz, ChunkStatus.f_62326_, false);
        if (centerChunk == null) {
            return level.m_204166_(pos);
        }
        LevelChunkSection[] sections = centerChunk.m_7103_();
        if (secIdx < 0 || secIdx >= sections.length) {
            return level.m_204166_(pos);
        }
        Holder<Biome> centerBiome = sectionSingleBiome(sections[secIdx]);
        if (centerBiome == null) {
            return level.m_204166_(pos);
        }
        int lx = x & 15;
        int ly = y & 15;
        int lz = z & 15;
        int dy = (ly < 2 && secIdx > 0) ? -1 : (ly > 13 && secIdx + 1 < sections.length ? 1 : 0);
        if (dy != 0 && sectionSingleBiome(sections[secIdx + dy]) != centerBiome) {
            return level.m_204166_(pos);
        }
        int dx = (lx < 2) ? -1 : (lx > 13 ? 1 : 0);
        int dz = (lz < 2) ? -1 : (lz > 13 ? 1 : 0);
        if (dx != 0 && !neighborChunkMatches(chunkCache, cx + dx, cz, secIdx, dy, centerBiome)) {
            return level.m_204166_(pos);
        }
        if (dz != 0 && !neighborChunkMatches(chunkCache, cx, cz + dz, secIdx, dy, centerBiome)) {
            return level.m_204166_(pos);
        }
        if (dx != 0 && dz != 0 && !neighborChunkMatches(chunkCache, cx + dx, cz + dz, secIdx, dy, centerBiome)) {
            return level.m_204166_(pos);
        }
        return centerBiome;
    }

    private static boolean neighborChunkMatches(
            ClientChunkCache chunkCache, int cx, int cz, int secIdx, int dy, Holder<Biome> expected) {
        LevelChunk neighbor = chunkCache.m_7587_(cx, cz, ChunkStatus.f_62326_, false);
        if (neighbor == null) {
            return false;
        }
        LevelChunkSection[] sections = neighbor.m_7103_();
        if (secIdx < 0 || secIdx >= sections.length || sectionSingleBiome(sections[secIdx]) != expected) {
            return false;
        }
        if (dy != 0) {
            int sy = secIdx + dy;
            if (sy < 0 || sy >= sections.length || sectionSingleBiome(sections[sy]) != expected) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static Holder<Biome> sectionSingleBiome(LevelChunkSection section) {
        if (section == null) {
            return null;
        }
        PalettedContainerRO<Holder<Biome>> biomes = section.m_187996_();
        if (biomes instanceof PalettedContainerBridge bridge) {
            Object val = bridge.sunlit$containerSingleValue();
            if (val instanceof Holder<?> holder) {
                return (Holder<Biome>) holder;
            }
        }
        return null;
    }

    /**
     * Returns {@code true} when the segment stays within 1 or 2 face-adjacent air voxels
     * (and therefore cannot hit any block collider or fluid), avoiding {@code Vec3} and
     * {@code ClipContext} allocations inside {@code CustomParticle.tickCollisions()}.
     */
    public static int airRayKind(int bx0, int by0, int bz0, int bx1, int by1, int bz1) {
        int manhattan = Math.abs(bx1 - bx0) + Math.abs(by1 - by0) + Math.abs(bz1 - bz0);
        return manhattan <= 1 ? manhattan : -1;
    }

    public static boolean isClearAirRay(
            ClientLevel level,
            BlockPos.MutableBlockPos scratchPos,
            double x0,
            double y0,
            double z0,
            double x1,
            double y1,
            double z1) {
        int bx0 = Mth.m_14107_(x0);
        int by0 = Mth.m_14107_(y0);
        int bz0 = Mth.m_14107_(z0);
        int bx1 = Mth.m_14107_(x1);
        int by1 = Mth.m_14107_(y1);
        int bz1 = Mth.m_14107_(z1);
        int kind = airRayKind(bx0, by0, bz0, bx1, by1, bz1);
        if (kind == 0) {
            BlockState s0 = level.m_8055_(scratchPos.m_122178_(bx0, by0, bz0));
            return s0.m_60795_();
        }
        if (kind == 1) {
            BlockState s0 = level.m_8055_(scratchPos.m_122178_(bx0, by0, bz0));
            if (!s0.m_60795_()) {
                return false;
            }
            BlockState s1 = level.m_8055_(scratchPos.m_122178_(bx1, by1, bz1));
            return s1.m_60795_();
        }
        return false;
    }

    /**
     * Returns active {@code BLOCK_*} particle descriptors for the current client tick without
     * re-iterating {@link ParticleLoader#particles} on every random block display tick.
     */
    public static ArrayList<ParticleData> getBlockFxCandidates(ClientLevel level) {
        return getBlockFxCandidates(level, scratch());
    }

    public static ArrayList<ParticleData> getBlockFxCandidates(ClientLevel level, Scratch scratch) {
        return getBlockFxCandidates(level, level.m_46467_(), scratch);
    }

    public static ArrayList<ParticleData> getBlockFxCandidates(Object levelKey, long gameTime, Scratch scratch) {
        Map<String, ParticleData> map = ParticleLoader.particles;
        int mapSize = map.size();
        int epoch = whitelistEpoch;
        if (scratch.cachedBlockFxLevel == levelKey
                && scratch.cachedBlockFxTick == gameTime
                && scratch.cachedBlockFxMap == map
                && scratch.cachedBlockFxMapSize == mapSize
                && scratch.cachedBlockFxEpoch == epoch) {
            return scratch.blockFxCandidates;
        }
        ArrayList<ParticleData> out = scratch.blockFxCandidates;
        out.clear();
        for (ParticleData data : map.values()) {
            if (!Boolean.TRUE.equals(data.enabled)) {
                continue;
            }
            ParticleData.SpawnPos sp = data.spawnPos;
            if (sp != ParticleData.SpawnPos.BLOCK_SIDES
                    && sp != ParticleData.SpawnPos.BLOCK_BOTTOM
                    && sp != ParticleData.SpawnPos.BLOCK_TOP) {
                continue;
            }
            if (data.weather != null
                    && levelKey instanceof ClientLevel cl
                    && Boolean.TRUE.equals(data.weather.isCurrent(cl))) {
                out.add(data);
            }
        }
        scratch.cachedBlockFxLevel = levelKey;
        scratch.cachedBlockFxTick = gameTime;
        scratch.cachedBlockFxMap = map;
        scratch.cachedBlockFxMapSize = mapSize;
        scratch.cachedBlockFxEpoch = epoch;
        return out;
    }
}
