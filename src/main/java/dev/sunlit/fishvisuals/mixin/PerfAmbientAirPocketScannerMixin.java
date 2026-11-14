package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.AmbientSoundsClientPerf;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import team.creative.ambientsounds.AmbientSounds;
import team.creative.ambientsounds.block.AmbientBlockGroup;
import team.creative.ambientsounds.engine.AmbientEngine;
import team.creative.ambientsounds.environment.pocket.AirPocket;
import team.creative.ambientsounds.environment.pocket.BlockDistribution;
import team.creative.creativecore.common.util.type.set.QuadBitSet;

@Pseudo
@Mixin(targets = "team.creative.ambientsounds.environment.pocket.AirPocketScanner", priority = 2000, remap = false)
public abstract class PerfAmbientAirPocketScannerMixin {

    @Shadow
    @Final
    public AmbientEngine engine;

    @Shadow
    @Final
    public Level level;

    @Shadow
    @Final
    public BlockPos origin;

    @Shadow
    private QuadBitSet sky;

    @Shadow
    private BlockPos.MutableBlockPos mutable;

    @Shadow
    private double distributionCounter;

    @Shadow
    private int totalSize;

    @Shadow
    private int currentDistance;

    @Shadow
    private int lightValueCounter;

    @Shadow
    private int skyLightValueCounter;

    @Shadow
    private int faceCounter;

    @Shadow
    private int air;

    @Shadow
    private Consumer<AirPocket> consumer;

    @Shadow
    protected abstract boolean hearThrough(LevelReader level, BlockState state, BlockPos pos);

    @Unique
    private int sunlit$lastChunkX = Integer.MIN_VALUE;

    @Unique
    private int sunlit$lastChunkZ = Integer.MIN_VALUE;

    @Unique
    private LevelChunk sunlit$lastChunk;

    @Unique
    private final BlockPos.MutableBlockPos sunlit$scanPos = new BlockPos.MutableBlockPos();

    @Unique
    private BlockState sunlit$getBlockState(Level level, BlockPos pos) {
        if (level.m_151570_(pos)) {
            return Blocks.f_50626_.m_49966_();
        }
        int cx = pos.m_123341_() >> 4;
        int cz = pos.m_123343_() >> 4;
        LevelChunk chunk = this.sunlit$lastChunk;
        if (chunk == null || cx != this.sunlit$lastChunkX || cz != this.sunlit$lastChunkZ) {
            chunk = level.m_6325_(cx, cz);
            this.sunlit$lastChunkX = cx;
            this.sunlit$lastChunkZ = cz;
            this.sunlit$lastChunk = chunk;
        }
        return chunk != null ? chunk.m_8055_(pos) : level.m_8055_(pos);
    }

    /**
     * @author better-sunlit
     * @reason Reuse chunk cache during vertical sky visibility scan
     */
    @Overwrite(remap = false)
    protected boolean canSeeSkyConsiderSolids(LevelReader level, BlockPos pos) {
        int x = pos.m_123341_();
        int z = pos.m_123343_();
        this.mutable.m_122178_(x, pos.m_123342_() + 1, z);
        if (!level.m_45527_(this.mutable)) {
            return false;
        }
        int height = level.m_6924_(Heightmap.Types.WORLD_SURFACE, x, z);
        Level levelRef = level instanceof Level l ? l : null;
        for (int y = this.mutable.m_123342_(); y <= height; y++) {
            this.mutable.m_142448_(y);
            BlockState state = levelRef != null ? this.sunlit$getBlockState(levelRef, this.mutable) : level.m_8055_(this.mutable);
            if (!this.hearThrough(level, state, this.mutable)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @author better-sunlit
     * @reason Replace HashMap&lt;BlockPosInspection&gt;, iterator, and Double boxing allocations with primitive wave queues
     */
    @Overwrite(remap = false)
    public void run() {
        try {
            int ox = this.origin.m_123341_();
            int oy = this.origin.m_123342_();
            int oz = this.origin.m_123343_();

            AmbientSoundsClientPerf.WaveQueue currentWave = new AmbientSoundsClientPerf.WaveQueue(64);
            AmbientSoundsClientPerf.WaveQueue nextWave = new AmbientSoundsClientPerf.WaveQueue(128);
            Reference2DoubleOpenHashMap<BlockState> foundWeights = new Reference2DoubleOpenHashMap<>();

            currentWave.addSeed(BlockPos.m_121882_(ox, oy, oz));

            int steps = 0;
            while (currentWave.size() > 0) {
                int distance = this.currentDistance;
                int waveSize = currentWave.size();
                for (int i = 0; i < waveSize; i++) {
                    long packed = currentWave.packedPosAt(i);
                    int dirMask = currentWave.dirMaskAt(i);
                    int px = BlockPos.m_121983_(packed);
                    int py = BlockPos.m_122008_(packed);
                    int pz = BlockPos.m_122015_(packed);

                    this.sunlit$scanPos.m_122178_(px, py, pz);
                    BlockState state = this.sunlit$getBlockState(this.level, this.sunlit$scanPos);
                    if (this.hearThrough(this.level, state, this.sunlit$scanPos)) {
                        if (!state.m_60795_()) {
                            double weight = this.engine.airWeightFactor(distance);
                            this.distributionCounter += weight;
                            foundWeights.addTo(state, weight);
                        }
                        if (distance < this.engine.airPocketDistance && this.air < this.engine.maxAirPocketCount) {
                            this.air++;
                        }
                        if (!(this.sky.get(px, pz)
                                && this.sky.get(px - 1, pz)
                                && this.sky.get(px, pz - 1)
                                && this.sky.get(px + 1, pz)
                                && this.sky.get(px, pz + 1))) {
                            for (int d = 0; d < 6; d++) {
                                if (this.totalSize > this.engine.airPocketCount) {
                                    break;
                                }
                                if (!AmbientSoundsClientPerf.canExpandOutward(d, px, py, pz, ox, oy, oz)) {
                                    continue;
                                }
                                Direction dir = AmbientSoundsClientPerf.DIRECTIONS[d];
                                long nextPacked = BlockPos.m_121882_(
                                        px + dir.m_122429_(),
                                        py + dir.m_122430_(),
                                        pz + dir.m_122431_());
                                int oppBit = 1 << dir.m_122424_().ordinal();
                                nextWave.addDirection(nextPacked, oppBit);
                                this.totalSize++;
                            }
                        }
                    } else {
                        if (!this.sky.get(px, pz)
                                && (dirMask & AmbientSoundsClientPerf.UP_MASK) != 0
                                && this.canSeeSkyConsiderSolids(this.level, this.sunlit$scanPos)) {
                            this.sky.set(px, pz);
                            if (distance < this.engine.airPocketDistance) {
                                this.air = this.engine.maxAirPocketCount;
                            }
                        }
                        for (int d = 0; d < 6; d++) {
                            if ((dirMask & (1 << d)) != 0) {
                                Direction dir = AmbientSoundsClientPerf.DIRECTIONS[d];
                                this.mutable.m_122178_(
                                        px + dir.m_122429_(),
                                        py + dir.m_122430_(),
                                        pz + dir.m_122431_());
                                this.lightValueCounter += this.level.m_7146_(this.mutable);
                                this.skyLightValueCounter += this.level.m_45517_(LightLayer.SKY, this.mutable);
                                this.faceCounter++;
                            }
                        }
                        double weight = this.engine.airWeightFactor(distance);
                        this.distributionCounter += weight;
                        foundWeights.addTo(state, weight);
                    }

                    steps++;
                    if (steps > AmbientSounds.CONFIG.scanStepAmount) {
                        steps = 0;
                        try {
                            Thread.sleep(1L);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
                this.currentDistance++;
                AmbientSoundsClientPerf.WaveQueue tmp = currentWave;
                currentWave = nextWave;
                nextWave = tmp;
                nextWave.clear();
            }

            HashMap<String, BlockDistribution> blocks = new HashMap<>();
            for (Map.Entry<String, AmbientBlockGroup> entry : this.engine.blockGroups.entrySet()) {
                BlockDistribution distribution = new BlockDistribution();
                AmbientBlockGroup group = entry.getValue();
                for (Reference2DoubleMap.Entry<BlockState> found : foundWeights.reference2DoubleEntrySet()) {
                    if (group.is(found.getKey())) {
                        distribution.add(found.getDoubleValue());
                    }
                }
                distribution.calculatePercentage(this.distributionCounter);
                blocks.put(entry.getKey(), distribution);
            }

            this.consumer.accept(new AirPocket(
                    this.engine,
                    blocks,
                    (double) this.lightValueCounter / (double) this.faceCounter,
                    (double) this.skyLightValueCounter / (double) this.faceCounter,
                    (double) this.air / (double) this.engine.maxAirPocketCount));
        } finally {
            this.sunlit$lastChunk = null;
        }
    }
}
