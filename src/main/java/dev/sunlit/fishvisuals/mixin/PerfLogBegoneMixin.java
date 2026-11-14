package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.LogBegonePerf;
import mod.azure.logbegone.LogBegoneConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import java.util.regex.Pattern;

/**
 * Eliminates per-log-line {@code Pattern.compile} and iterator churn in LogBegone's
 * {@code shouldFilterMessage(String)}.
 */
@Pseudo
@Mixin(targets = "mod.azure.logbegone.LogBegoneMod", priority = 2000, remap = false)
public abstract class PerfLogBegoneMixin {
    @Unique private static volatile boolean sunlit$filterReady;

    @Inject(method = "<init>()V", at = @At("RETURN"))
    private void sunlit$deferFilterWarmup(CallbackInfo ci) {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(
                (FMLLoadCompleteEvent event) -> event.enqueueWork(PerfLogBegoneMixin::sunlit$enableFilter));
    }

    @Unique private static void sunlit$enableFilter() {
        LogBegonePerf.preload();
        sunlit$filterReady = true;
    }

    /**
     * @author Better Sunlit
     * @reason Precompile regexes and reuse ThreadLocal Matchers over an immutable snapshot
     */
    @Overwrite
    public static boolean shouldFilterMessage(String message) {
        // Never resolve addon helper classes while holding the Mixin/logging locks during
        // parallel mod construction. Both the flag and this fallback are merged into LogBegone.
        if (!sunlit$filterReady) {
            if (message == null) return true;
            for (String phrase : LogBegoneConfig.COMMON.phrases.get()) {
                if (phrase != null && message.contains(phrase)) return true;
            }
            for (String regex : LogBegoneConfig.COMMON.regex.get()) {
                if (regex != null && Pattern.matches(regex, message)) return true;
            }
            return false;
        }
        return LogBegonePerf.shouldFilter(
                message,
                LogBegoneConfig.COMMON.phrases.get(),
                LogBegoneConfig.COMMON.regex.get());
    }
}
