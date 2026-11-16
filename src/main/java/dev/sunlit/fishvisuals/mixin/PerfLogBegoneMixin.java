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
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Eliminates per-log-line {@code Pattern.compile} and iterator churn in LogBegone's
 * {@code shouldFilterMessage(String)}.
 */
@Pseudo
@Mixin(targets = "mod.azure.logbegone.LogBegoneMod", priority = 2000, remap = false)
public abstract class PerfLogBegoneMixin {
    @Unique private static volatile boolean sunlit$filterReady;
    @Unique private static volatile String[] sunlit$earlyRegexes;
    @Unique private static volatile Pattern[] sunlit$earlyPatterns;
    @Unique private static final ThreadLocal<Matcher[]> sunlit$earlyMatchers = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Pattern[]> sunlit$earlyMatcherOwner = new ThreadLocal<>();

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
            java.util.List<? extends String> regexList = LogBegoneConfig.COMMON.regex.get();
            if (regexList == null || regexList.isEmpty()) return false;
            Pattern[] patterns = sunlit$getEarlyPatterns(regexList);
            Matcher[] matchers = sunlit$earlyMatchers.get();
            if (matchers == null || sunlit$earlyMatcherOwner.get() != patterns) {
                matchers = new Matcher[patterns.length];
                for (int i = 0; i < patterns.length; i++) {
                    if (patterns[i] != null) matchers[i] = patterns[i].matcher("");
                }
                sunlit$earlyMatchers.set(matchers);
                sunlit$earlyMatcherOwner.set(patterns);
            }
            for (Matcher m : matchers) {
                if (m == null) continue;
                try {
                    if (m.reset(message).matches()) return true;
                } finally {
                    m.reset("");
                }
            }
            return false;
        }
        return LogBegonePerf.shouldFilter(
                message,
                LogBegoneConfig.COMMON.phrases.get(),
                LogBegoneConfig.COMMON.regex.get());
    }

    @Unique
    private static Pattern[] sunlit$getEarlyPatterns(List<? extends String> regexList) {
        String[] saved = sunlit$earlyRegexes;
        Pattern[] patterns = sunlit$earlyPatterns;
        int size = regexList.size();
        if (saved != null && patterns != null && saved.length == size) {
            boolean same = true;
            for (int i = 0; i < size; i++) {
                if (!Objects.equals(saved[i], regexList.get(i))) {
                    same = false;
                    break;
                }
            }
            if (same) return patterns;
        }
        String[] nextSaved = new String[size];
        Pattern[] nextPatterns = new Pattern[size];
        for (int i = 0; i < size; i++) {
            String r = regexList.get(i);
            nextSaved[i] = r;
            if (r != null) nextPatterns[i] = Pattern.compile(r);
        }
        sunlit$earlyRegexes = nextSaved;
        sunlit$earlyPatterns = nextPatterns;
        return nextPatterns;
    }
}
