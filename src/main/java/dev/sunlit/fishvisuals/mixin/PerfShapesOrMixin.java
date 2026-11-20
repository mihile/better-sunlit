package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.VoxelOptimizePerf;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Single-pass {@code Shapes.or} ({@code m_83124_}/{@code m_83110_}) and OR {@code joinUnoptimized} ({@code m_83148_}). */
@Mixin(value = Shapes.class, remap = false)
public abstract class PerfShapesOrMixin {
    @Inject(method = "m_83124_", at = @At("HEAD"), cancellable = true)
    private static void betterSunlit$fastOrVarargs(
            VoxelShape first, VoxelShape[] others, CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShape merged = VoxelOptimizePerf.orAll(first, others);
        if (merged != null) {
            cir.setReturnValue(merged);
        }
    }

    @Inject(method = "m_83110_", at = @At("HEAD"), cancellable = true)
    private static void betterSunlit$fastOrTwo(
            VoxelShape first, VoxelShape second, CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShape merged = VoxelOptimizePerf.orTwo(first, second);
        if (merged != null) {
            cir.setReturnValue(merged);
        }
    }

    @Inject(method = "m_83148_", at = @At("HEAD"), cancellable = true)
    private static void betterSunlit$fastJoinUnoptimizedOr(
            VoxelShape first, VoxelShape second, BooleanOp op, CallbackInfoReturnable<VoxelShape> cir) {
        if (op == BooleanOp.f_82695_) {
            VoxelShape merged = VoxelOptimizePerf.orTwo(first, second);
            if (merged != null) {
                cir.setReturnValue(merged);
            }
        }
    }
}
