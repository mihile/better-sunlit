package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.CrossCollisionShapePerf;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CrossCollisionBlock.class, remap = false, priority = 900)
public abstract class PerfCrossCollisionBlockMixin {
    @Inject(method = "m_52326_(FFFFF)[Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("HEAD"), cancellable = true)
    private void betterSunlit$useCachedMakeShapes(
            float nodeWidth, float extensionWidth, float nodeHeight, float extensionHeight, float collisionHeight,
            CallbackInfoReturnable<VoxelShape[]> cir) {
        VoxelShape[] cached = CrossCollisionShapePerf.get(
                nodeWidth, extensionWidth, nodeHeight, extensionHeight, collisionHeight);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "m_52326_(FFFFF)[Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("RETURN"))
    private void betterSunlit$recordMakeShapes(
            float nodeWidth, float extensionWidth, float nodeHeight, float extensionHeight, float collisionHeight,
            CallbackInfoReturnable<VoxelShape[]> cir) {
        CrossCollisionShapePerf.put(
                nodeWidth, extensionWidth, nodeHeight, extensionHeight, collisionHeight, cir.getReturnValue());
    }
}
