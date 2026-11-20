package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.WallShapePerf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(value = WallBlock.class, remap = false, priority = 900)
public abstract class PerfWallBlockMixin extends Block {
    private PerfWallBlockMixin(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Inject(method = "m_57965_(FFFFFF)Ljava/util/Map;", at = @At("HEAD"), cancellable = true)
    private void betterSunlit$useIndexedShapeCache(
            float f1, float f2, float f3, float f4, float f5, float f6,
            CallbackInfoReturnable<Map<BlockState, VoxelShape>> cir) {
        Map<BlockState, VoxelShape> cached = WallShapePerf.tryBuildFromCache(
                this.m_49965_(), f1, f2, f3, f4, f5, f6);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "m_57965_(FFFFFF)Ljava/util/Map;", at = @At("RETURN"))
    private void betterSunlit$recordIndexedShapeCache(
            float f1, float f2, float f3, float f4, float f5, float f6,
            CallbackInfoReturnable<Map<BlockState, VoxelShape>> cir) {
        WallShapePerf.recordIfMissing(
                this.m_49965_(), f1, f2, f3, f4, f5, f6, cir.getReturnValue());
    }
}
