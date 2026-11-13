package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ParticleRainClientPerf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pigcart.particlerain.ParticleLoader;
import pigcart.particlerain.ParticleSpawner;
import pigcart.particlerain.VersionUtil;
import pigcart.particlerain.config.ParticleData;
import pigcart.particlerain.particle.StreakParticle;

@Pseudo
@Mixin(targets = "pigcart.particlerain.ParticleSpawner", remap = false)
public abstract class PerfParticleSpawnerMixin {
    @Shadow
    @Final
    private static BlockPos.MutableBlockPos pos;

    @Shadow
    private static float density(ParticleData data) {
        throw new AssertionError();
    }

    @Inject(method = "calculateHeight(Lnet/minecraft/client/multiplayer/ClientLevel;II)I",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private static void sunlit$calculateHeightNoAlloc(ClientLevel level, int x, int z,
                                                      CallbackInfoReturnable<Integer> cir) {
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
        cir.setReturnValue(y + 1);
    }

    @Inject(method = "tickBlockFX(Lnet/minecraft/core/BlockPos$MutableBlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private static void sunlit$tickBlockFXFast(BlockPos.MutableBlockPos origin, BlockState originState,
                                               RandomSource random, CallbackInfo ci) {
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (ParticleSpawner.spawnAttemptsUntilBlockFXIdle <= 0 && level.m_213780_().m_188501_() < 0.9f) {
            ci.cancel();
            return;
        }
        ParticleSpawner.spawnAttemptsUntilBlockFXIdle--;
        if (!originState.m_60812_(level, origin).m_83281_() && !ParticleSpawner.isIgnored(originState)) {
            ci.cancel();
            return;
        }

        ParticleRainClientPerf.Scratch scratch = ParticleRainClientPerf.scratch();
        Vector3f offset = scratch.v0;
        Holder<Biome> biome = null;
        Biome.Precipitation precip = null;
        int originIgnored = -1;

        for (ParticleData data : ParticleLoader.particles.values()) {
            if (!Boolean.TRUE.equals(data.enabled) || !Boolean.TRUE.equals(data.weather.isCurrent(level))) {
                continue;
            }
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
            if (biome == null) {
                biome = level.m_204166_(origin);
                precip = VersionUtil.getPrecipitationAt(level, biome, origin);
            }
            if (!data.precipitation.contains(precip)) continue;
            if (!(density(data) > random.m_188501_())) continue;
            if (!data.biomeList.contains(biome)) continue;
            if (!data.blockList.contains(adjState.m_222976_())) continue;
            if (Boolean.TRUE.equals(data.needsSkyAccess)
                    && origin.m_123342_() < ParticleSpawner.getHeight(level, origin.m_123341_(), origin.m_123343_())) {
                continue;
            }

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
        ci.cancel();
    }
}
