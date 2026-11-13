package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ParticleRainClientPerf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import pigcart.particlerain.ParticleLoader;
import pigcart.particlerain.ParticleSpawner;
import pigcart.particlerain.VersionUtil;
import pigcart.particlerain.config.ConfigData;
import pigcart.particlerain.config.ConfigManager;
import pigcart.particlerain.config.ParticleData;
import pigcart.particlerain.particle.StreakParticle;

import java.util.ArrayList;

@Pseudo
@Mixin(targets = "pigcart.particlerain.ParticleSpawner", remap = false)
public abstract class PerfParticleSpawnerMixin {
    @Shadow
    @Final
    private static BlockPos.MutableBlockPos pos;

    @Shadow
    @Final
    private static BlockPos.MutableBlockPos heightmapPos;

    @Shadow
    @Final
    private static RandomSource RANDOM;

    @Shadow
    private static float density(ParticleData data) {
        throw new AssertionError();
    }

    /**
     * Zero-allocation replacement using thread-local {@link ParticleRainClientPerf.Scratch#cursor}.
     *
     * @author better-sunlit
     * @reason Eliminate per-column BlockPos.MutableBlockPos allocation and CallbackInfo overhead
     */
    @Overwrite(remap = false)
    public static int calculateHeight(ClientLevel level, int x, int z) {
        int y = level.m_6924_(Heightmap.Types.MOTION_BLOCKING, x, z);
        if (y == 0 || y == -1) {
            y = VersionUtil.getMaxY(level);
        }
        BlockPos.MutableBlockPos cursor = ParticleRainClientPerf.scratch().cursor;
        cursor.m_122178_(x, y, z);
        int minY = VersionUtil.getMinY(level);
        while (y > minY) {
            BlockState state = level.m_8055_(cursor);
            boolean emptyFluid = state.m_60819_().m_76178_();
            if (!emptyFluid || (!state.m_60812_(level, cursor).m_83281_() && !ParticleSpawner.isIgnored(state))) {
                break;
            }
            y--;
            cursor.m_142448_(y);
        }
        return y + 1;
    }

    /**
     * Filters enabled block-FX particles matching the active weather up front and delays biome /
     * precipitation lookups until cheaper adjacent block/sky checks pass.
     *
     * @author better-sunlit
     * @reason Avoid wasted shape/biome/precipitation lookups per random block tick
     */
    @Overwrite(remap = false)
    public static void tickBlockFX(BlockPos.MutableBlockPos origin, BlockState originState, RandomSource random) {
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (ParticleSpawner.spawnAttemptsUntilBlockFXIdle <= 0 && level.m_213780_().m_188501_() < 0.9f) {
            return;
        }
        ParticleSpawner.spawnAttemptsUntilBlockFXIdle--;

        ParticleRainClientPerf.Scratch scratch = ParticleRainClientPerf.scratch();
        ArrayList<ParticleData> candidates = scratch.candidates;
        candidates.clear();
        for (ParticleData data : ParticleLoader.particles.values()) {
            if (!Boolean.TRUE.equals(data.enabled)) {
                continue;
            }
            ParticleData.SpawnPos sp = data.spawnPos;
            if (sp != ParticleData.SpawnPos.BLOCK_SIDES
                    && sp != ParticleData.SpawnPos.BLOCK_BOTTOM
                    && sp != ParticleData.SpawnPos.BLOCK_TOP) {
                continue;
            }
            if (data.weather != null && Boolean.TRUE.equals(data.weather.isCurrent(level))) {
                candidates.add(data);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        if (!originState.m_60812_(level, origin).m_83281_() && !ParticleSpawner.isIgnored(originState)) {
            return;
        }

        Vector3f offset = scratch.v0;
        Holder<Biome> biome = null;
        Biome.Precipitation precip = null;
        int originIgnored = -1;
        int candidateCount = candidates.size();

        for (int i = 0; i < candidateCount; i++) {
            ParticleData data = candidates.get(i);
            Direction spawnDir;
            switch (data.spawnPos) {
                case BLOCK_SIDES -> spawnDir = Direction.Plane.HORIZONTAL.m_235690_(random);
                case BLOCK_BOTTOM -> spawnDir = Direction.DOWN;
                case BLOCK_TOP -> spawnDir = Direction.UP;
                default -> { continue; }
            }
            Direction stepDir = spawnDir.m_122424_();
            pos.m_122178_(
                    origin.m_123341_() + stepDir.m_122429_(),
                    origin.m_123342_() + stepDir.m_122430_(),
                    origin.m_123343_() + stepDir.m_122431_());
            BlockState adjState = level.m_8055_(pos);
            FluidState adjFluid = adjState.m_60819_();
            VoxelShape adjShape = adjState.m_60812_(level, pos);
            if (adjShape.m_83281_() && adjFluid.m_76178_()) {
                continue;
            }
            if (originIgnored < 0) {
                originIgnored = ParticleSpawner.isIgnored(originState) ? 1 : 0;
            }
            if (originIgnored == 1) {
                continue;
            }
            if (!data.blockList.contains(adjState.m_222976_())) continue;
            if (Boolean.TRUE.equals(data.needsSkyAccess)
                    && origin.m_123342_() < ParticleSpawner.getHeight(level, origin.m_123341_(), origin.m_123343_())) {
                continue;
            }
            if (!(density(data) > random.m_188501_())) continue;
            if (biome == null) {
                biome = level.m_204166_(origin);
                precip = VersionUtil.getPrecipitationAt(level, biome, origin);
            }
            if (!data.precipitation.contains(precip)) continue;
            if (!data.biomeList.contains(biome)) continue;

            float r1 = random.m_188501_();
            float r2 = random.m_188501_();
            double edge;
            if (spawnDir.m_122421_() == Direction.AxisDirection.POSITIVE) {
                edge = adjShape.m_83290_(spawnDir.m_122434_(), r1, r2);
                if (spawnDir == Direction.UP) {
                    edge = Math.max(edge, (double) adjFluid.m_76155_(level, pos));
                }
                if (edge == Double.NEGATIVE_INFINITY) continue;
                edge += 0.01D;
            } else {
                edge = adjShape.m_166078_(spawnDir.m_122434_(), r1, r2);
                if (edge == Double.POSITIVE_INFINITY) continue;
                edge -= 0.01D;
            }
            offset.set(r2 - 0.5f, (float) edge - 0.5f, r1 - 0.5f);
            switch (spawnDir) {
                case NORTH, SOUTH -> offset.rotate(scratch.q0.rotationXYZ(1.5707964f, 1.5707964f, 0.0f));
                case EAST, WEST -> offset.rotate(scratch.q0.rotationXYZ(-1.5707964f, 0.0f, -1.5707964f));
                default -> {}
            }
            float sx = (float) pos.m_123341_() + offset.x + 0.5f;
            float sy = (float) pos.m_123342_() + offset.y + 0.5f;
            float sz = (float) pos.m_123343_() + offset.z + 0.5f;
            if (data.particleStyle == ParticleData.ParticleStyle.REGISTERED
                    && "particlerain:streak".equals(data.registeredParticleId)) {
                Minecraft.m_91087_().f_91061_.m_107344_(
                        new StreakParticle(level, sx, sy, sz, spawnDir, data.blockList));
            } else {
                data.particleStyle.spawn(level, sx, sy, sz, data);
            }
            ParticleSpawner.spawnAttemptsUntilBlockFXIdle = 10000;
        }
    }

    /**
     * Pre-filters active SKY particles once per tick, skips clear ticks immediately, and only
     * queries biome/precipitation/block after density passes.
     *
     * @author better-sunlit
     * @reason Eliminate per-iteration weather filtering and redundant getBiome lookups
     */
    @Overwrite(remap = false)
    public static void tickSkyFX(ClientLevel level, Vec3 playerPos) {
        ParticleRainClientPerf.Scratch scratch = ParticleRainClientPerf.scratch();
        ArrayList<ParticleData> candidates = scratch.candidates;
        candidates.clear();
        float maxDensity = 0.0f;
        for (ParticleData data : ParticleLoader.particles.values()) {
            if (!Boolean.TRUE.equals(data.enabled) || data.spawnPos != ParticleData.SpawnPos.SKY) {
                continue;
            }
            if (data.weather == null || !Boolean.TRUE.equals(data.weather.isCurrent(level))) {
                continue;
            }
            float d = density(data);
            if (d > 0.0f) {
                candidates.add(data);
                if (d > maxDensity) {
                    maxDensity = d;
                }
            }
        }
        if (candidates.isEmpty()) {
            if (ParticleSpawner.ticksUntilSkyFXIdle > 0) {
                ParticleSpawner.ticksUntilSkyFXIdle--;
            }
            return;
        }

        float speed = (float) Minecraft.m_91087_().m_91288_().m_20184_().m_82553_();
        int densityCount = ParticleSpawner.getSkyParticleDensity(level, speed);
        ConfigData config = ConfigManager.getConfig();
        float particleDist = (float) config.perf.particleDistance;
        boolean doHeightLimit = config.compat.doSpawnHeightLimit;
        int configuredHeightLimit = config.compat.spawnHeightLimit;
        boolean useHeightmapTemp = config.compat.useHeightmapTemp;
        int candidateCount = candidates.size();

        for (int i = 0; i < densityCount; i++) {
            float rand;
            if ((double) speed < 0.8D) {
                rand = Mth.m_14154_(Mth.m_14207_(RANDOM.m_188501_()) - Mth.m_14207_(RANDOM.m_188501_())) * -1.0f + 1.0f;
            } else {
                rand = RANDOM.m_188501_();
            }
            float theta = 6.2831855f * RANDOM.m_188501_();
            float phi = (float) Math.acos((double) (2.0f * rand - 1.0f));
            float sinPhi = Mth.m_14031_(phi);
            float x = particleDist * sinPhi * Mth.m_14089_(theta) + (float) playerPos.f_82479_;
            float y = particleDist * Mth.m_14089_(phi) + (float) playerPos.f_82480_;
            float z = particleDist * sinPhi * Mth.m_14031_(theta) + (float) playerPos.f_82481_;
            pos.m_122169_((double) x, (double) y, (double) z);
            if (!level.m_46749_(pos)) {
                continue;
            }
            if (doHeightLimit) {
                int limit = configuredHeightLimit == 0
                        ? VersionUtil.getCloudHeight(level, pos)
                        : configuredHeightLimit;
                if (limit != 0 && y > (float) limit) {
                    y = (float) limit;
                    pos.m_142448_(limit);
                }
            }
            int height = ParticleSpawner.getHeight(level, pos.m_123341_(), pos.m_123343_());
            if (height >= pos.m_123342_()) {
                continue;
            }
            float spawnChance = RANDOM.m_188501_();
            if (spawnChance >= maxDensity) {
                continue;
            }

            heightmapPos.m_122169_((double) x, (double) (height - 1), (double) z);
            Holder<Biome> biome = null;
            Biome.Precipitation precip = null;
            Holder<Block> surfaceBlock = null;
            for (int c = 0; c < candidateCount; c++) {
                ParticleData data = candidates.get(c);
                if (!(density(data) > spawnChance)) {
                    continue;
                }
                if (biome == null) {
                    biome = level.m_204166_(pos);
                    precip = VersionUtil.getPrecipitationAt(level, biome, useHeightmapTemp ? heightmapPos : pos);
                }
                if (!data.precipitation.contains(precip) || !data.biomeList.contains(biome)) {
                    continue;
                }
                if (surfaceBlock == null) {
                    surfaceBlock = level.m_8055_(heightmapPos).m_222976_();
                }
                if (!data.blockList.contains(surfaceBlock)) {
                    continue;
                }
                data.particleStyle.spawn(level, (double) x, (double) y, (double) z, data);
                ParticleSpawner.ticksUntilSkyFXIdle = 100;
            }
        }
    }

    /**
     * Pre-filters active WORLD_SURFACE particles once per tick, skips clear ticks immediately, and
     * delays biome/precipitation lookups until density and blockList pass.
     *
     * @author better-sunlit
     * @reason Eliminate per-tick clear-weather biome/height lookups and short-circuit density/blocks
     */
    @Overwrite(remap = false)
    public static void tickSurfaceFX(ClientLevel level, Vec3 playerPos) {
        ParticleRainClientPerf.Scratch scratch = ParticleRainClientPerf.scratch();
        ArrayList<ParticleData> candidates = scratch.candidates;
        candidates.clear();
        float maxDensity = 0.0f;
        for (ParticleData data : ParticleLoader.particles.values()) {
            if (!Boolean.TRUE.equals(data.enabled) || data.spawnPos != ParticleData.SpawnPos.WORLD_SURFACE) {
                continue;
            }
            if (data.weather == null || !Boolean.TRUE.equals(data.weather.isCurrent(level))) {
                continue;
            }
            float d = density(data);
            if (d > 0.0f) {
                candidates.add(data);
                if (d > maxDensity) {
                    maxDensity = d;
                }
            }
        }
        if (candidates.isEmpty()) {
            if (ParticleSpawner.ticksUntilSurfaceFXIdle > 0) {
                ParticleSpawner.ticksUntilSurfaceFXIdle--;
            }
            return;
        }

        ConfigData.PerformanceOptions perf = ConfigManager.getConfig().perf;
        int count;
        if (ParticleSpawner.ticksUntilSurfaceFXIdle <= 0) {
            count = 1;
        } else {
            count = perf.particleAmount / 200;
            ParticleSpawner.ticksUntilSurfaceFXIdle--;
        }
        double surfaceRange = (double) perf.surfaceRange;
        int candidateCount = candidates.size();

        for (int i = 0; i < count; i++) {
            double x = RANDOM.m_216328_(playerPos.f_82479_, surfaceRange);
            double z = RANDOM.m_216328_(playerPos.f_82481_, surfaceRange);
            double y = (double) ParticleSpawner.getHeight(level, Mth.m_14107_(x), Mth.m_14107_(z));
            heightmapPos.m_122169_(x, y - 1.0D, z);
            if (!level.m_46749_(heightmapPos)) {
                continue;
            }
            float spawnChance = RANDOM.m_188501_();
            if (spawnChance >= maxDensity) {
                continue;
            }

            Holder<Block> blockHolder = level.m_8055_(heightmapPos).m_222976_();
            Holder<Biome> biome = null;
            Biome.Precipitation precip = null;
            for (int c = 0; c < candidateCount; c++) {
                ParticleData data = candidates.get(c);
                if (!(density(data) > spawnChance) || !data.blockList.contains(blockHolder)) {
                    continue;
                }
                if (biome == null) {
                    biome = level.m_204166_(heightmapPos);
                    precip = VersionUtil.getPrecipitationAt(level, biome, heightmapPos);
                }
                if (!data.precipitation.contains(precip) || !data.biomeList.contains(biome)) {
                    continue;
                }
                data.particleStyle.spawn(level, x, y + RANDOM.m_188500_() + 0.1D, z, data);
                ParticleSpawner.ticksUntilSurfaceFXIdle = 100;
            }
        }
    }
}
