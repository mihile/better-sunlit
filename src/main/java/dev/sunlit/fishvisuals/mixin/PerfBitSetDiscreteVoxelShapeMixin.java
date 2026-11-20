package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.VoxelJoinPerf;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.BitSet;

/**
 * Exposes {@code BitSetDiscreteVoxelShape}'s private bit storage and dirty bounds to
 * {@link VoxelJoinPerf#join}; the {@code m_82641_} body is rewritten in
 * {@code EntryPostApplyPatches#patchBitSetDiscreteVoxelShape}.
 */
@Mixin(value = BitSetDiscreteVoxelShape.class, remap = false, priority = 900)
public abstract class PerfBitSetDiscreteVoxelShapeMixin implements VoxelJoinPerf.BitSetShapeTarget {
    @Shadow
    @Final
    private BitSet f_82580_;
    @Shadow
    private int f_82581_;
    @Shadow
    private int f_82582_;
    @Shadow
    private int f_82583_;
    @Shadow
    private int f_82584_;
    @Shadow
    private int f_82585_;
    @Shadow
    private int f_82586_;

    @Override
    public BitSet sunlitGetStorage() {
        return this.f_82580_;
    }

    @Override
    public int sunlitGetXSize() {
        return ((DiscreteVoxelShape) (Object) this).m_82828_();
    }

    @Override
    public int sunlitGetYSize() {
        return ((DiscreteVoxelShape) (Object) this).m_82845_();
    }

    @Override
    public int sunlitGetZSize() {
        return ((DiscreteVoxelShape) (Object) this).m_82852_();
    }

    @Override
    public void sunlitSetBounds(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax) {
        this.f_82581_ = xMin;
        this.f_82582_ = yMin;
        this.f_82583_ = zMin;
        this.f_82584_ = xMax;
        this.f_82585_ = yMax;
        this.f_82586_ = zMax;
    }
}
