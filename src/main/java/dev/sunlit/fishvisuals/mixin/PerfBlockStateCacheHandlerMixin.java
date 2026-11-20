package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.BlockStateCachePerf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Parallelizes ModernFix's {@code BlockStateCacheHandler.invalidateCache} sweep during registry freeze.
 */
@Mixin(targets = "org.embeddedt.modernfix.blockstate.BlockStateCacheHandler", remap = false)
public abstract class PerfBlockStateCacheHandlerMixin {
    @Inject(method = "invalidateCache", at = @At("HEAD"), cancellable = true)
    private static void sunlit$parallelInvalidateCache(CallbackInfo ci) {
        synchronized (BlockBehaviour.BlockStateBase.class) {
            @SuppressWarnings("unchecked")
            PerfIdMapperAccessor<BlockState> accessor = (PerfIdMapperAccessor<BlockState>) (Object) Block.f_49791_;
            if (BlockStateCachePerf.invalidateAllParallel(accessor.sunlit$getList())) {
                ci.cancel();
            }
        }
    }
}
