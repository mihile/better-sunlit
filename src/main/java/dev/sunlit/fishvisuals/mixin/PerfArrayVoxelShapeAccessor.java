package dev.sunlit.fishvisuals.mixin;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes package-private {@link ArrayVoxelShape} list constructor for fast single-pass optimization. */
@Mixin(value = ArrayVoxelShape.class, remap = false)
public interface PerfArrayVoxelShapeAccessor {
    @Invoker("<init>")
    static ArrayVoxelShape sunlitCreate(
            DiscreteVoxelShape shape, DoubleList xs, DoubleList ys, DoubleList zs) {
        throw new AssertionError();
    }
}
