package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.ReflectionPerf;
import me.jellysquid.mods.lithium.common.reflection.ReflectionUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = ReflectionUtil.class, remap = false)
public class PerfRadiumReflectionMixin {
    /**
     * @author BetterSunlit
     * @reason Avoid NoSuchMethodException stacktrace allocations during blockstate property analysis
     */
    @Overwrite(remap = false)
    public static boolean hasMethodOverride(
            Class<?> clazz,
            Class<?> superClass,
            boolean fallbackResult,
            String methodName,
            Class<?>... methodArgs) {
        return ReflectionPerf.hasMethodOverride(clazz, superClass, fallbackResult, methodName, methodArgs);
    }
}
