package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.VoxelOptimizePerf;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Single-pass {@code VoxelShape.optimize()} ({@code m_83296_}) avoiding {@code O(B^2)} joins. */
@Mixin(value = VoxelShape.class, remap = false)
public abstract class PerfVoxelShapeMixin implements VoxelOptimizePerf.OptMarker {
    @Unique
    private boolean sunlitOptimized;

    @Override
    public boolean sunlitIsOptimized() {
        return this.sunlitOptimized;
    }

    @Override
    public void sunlitMarkOptimized() {
        this.sunlitOptimized = true;
    }

    @Inject(method = "m_83296_", at = @At("HEAD"), cancellable = true)
    private void betterSunlit$fastOptimize(CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShape opt = VoxelOptimizePerf.optimize((VoxelShape) (Object) this);
        if (opt != null) {
            cir.setReturnValue(opt);
        }
    }
}
