package dev.sunlit.fishvisuals.mixin;

import net.irisshaders.iris.gl.IrisRenderSystem;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL44C;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Arrays;

@Mixin(value = IrisRenderSystem.class, remap = false)
public abstract class PerfOculusSamplerUnbindMixin {
    @Shadow private static int[] samplers;
    @Shadow private static boolean hasMultibind;
    @Unique private static int sunlit$highestSampler = -1;

    @Inject(method = "initRenderer()V", at = @At("RETURN"))
    private static void sunlit$resetSamplerRange(CallbackInfo ci) { sunlit$highestSampler = -1; }

    @Inject(method = "bindSamplerToUnit(II)V", at = @At("RETURN"))
    private static void sunlit$trackSampler(int unit, int sampler, CallbackInfo ci) {
        if (sampler != 0 && unit > sunlit$highestSampler) sunlit$highestSampler = unit;
    }
    /** @author Better Sunlit @reason Skip empty resets and clear only the used sampler range; null GL array means zero bindings. */
    @Overwrite public static void unbindAllSamplers() {
        int count = sunlit$highestSampler + 1;
        if (count == 0) return;
        if (hasMultibind) GL44C.nglBindSamplers(0, count, 0L);
        else for (int i = 0; i < count; i++) if (samplers[i] != 0) GL33C.glBindSampler(i, 0);
        Arrays.fill(samplers, 0, count, 0);
        sunlit$highestSampler = -1;
    }
}
