package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.LogBegonePerf;
import mod.azure.logbegone.LogBegoneConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Eliminates per-log-line {@code Pattern.compile} and iterator churn in LogBegone's
 * {@code shouldFilterMessage(String)}.
 */
@Pseudo
@Mixin(targets = "mod.azure.logbegone.LogBegoneMod", priority = 2000, remap = false)
public abstract class PerfLogBegoneMixin {

    /**
     * @author Better Sunlit
     * @reason Precompile regexes and reuse ThreadLocal Matchers over an immutable snapshot
     */
    @Overwrite
    public static boolean shouldFilterMessage(String message) {
        return LogBegonePerf.shouldFilter(
                message,
                LogBegoneConfig.COMMON.phrases.get(),
                LogBegoneConfig.COMMON.regex.get());
    }
}
