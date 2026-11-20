package dev.sunlit.fishvisuals.mixin;

import mod.azure.logbegone.LogBegoneConfig;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.util.StringBuilderFormattable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Eliminates per-log-event {@code getFormattedMessage()} + {@code invokedynamic} string
 * concatenation in LogBegone's {@link mod.azure.logbegone.JavaUtilLog4jFilter#filter(LogEvent)}
 * while preserving exact substring/regex matching and without loading cold helper classes on startup.
 */
@Pseudo
@Mixin(targets = "mod.azure.logbegone.JavaUtilLog4jFilter", priority = 2000, remap = false)
public abstract class PerfJavaUtilLog4jFilterMixin {
    @Unique
    private static final ThreadLocal<StringBuilder> sunlit$sb =
            ThreadLocal.withInitial(() -> new StringBuilder(128));
    @Unique
    private static final ThreadLocal<Matcher[]> sunlit$tlMatchers = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<Pattern[]> sunlit$tlMatcherOwner = new ThreadLocal<>();
    @Unique
    private static volatile Object sunlit$cachedPhraseList;
    @Unique
    private static volatile String[] sunlit$cachedPhrases = new String[0];
    @Unique
    private static volatile String[] sunlit$cachedRegexes;
    @Unique
    private static volatile Pattern[] sunlit$cachedPatterns;

    /**
     * @author Better Sunlit
     * @reason Format LogEvent into reusable ThreadLocal StringBuilder instead of allocating Strings per log event
     */
    @Overwrite
    public Filter.Result filter(LogEvent event) {
        if (event == null) {
            return Filter.Result.NEUTRAL;
        }
        StringBuilder sb = sunlit$sb.get();
        sb.setLength(0);
        String loggerName = event.getLoggerName();
        sb.append(loggerName);
        Message msg = event.getMessage();
        if (msg instanceof StringBuilderFormattable formattable) {
            formattable.formatTo(sb);
        } else if (msg != null) {
            sb.append(msg.getFormattedMessage());
        } else {
            sb.append("null");
        }

        List<? extends String> phrasesList = LogBegoneConfig.COMMON.phrases.get();
        if (phrasesList != null && !phrasesList.isEmpty()) {
            String[] phrases = sunlit$getPhrases(phrasesList);
            for (String phrase : phrases) {
                if (phrase != null && sb.indexOf(phrase) >= 0) {
                    return Filter.Result.DENY;
                }
            }
        }

        List<? extends String> regexList = LogBegoneConfig.COMMON.regex.get();
        if (regexList == null || regexList.isEmpty()) {
            return Filter.Result.NEUTRAL;
        }

        Pattern[] patterns = sunlit$getPatterns(regexList);
        Matcher[] matchers = sunlit$tlMatchers.get();
        if (matchers == null || sunlit$tlMatcherOwner.get() != patterns) {
            matchers = new Matcher[patterns.length];
            for (int i = 0; i < patterns.length; i++) {
                if (patterns[i] != null) {
                    matchers[i] = patterns[i].matcher("");
                }
            }
            sunlit$tlMatchers.set(matchers);
            sunlit$tlMatcherOwner.set(patterns);
        }
        for (Matcher m : matchers) {
            if (m == null) {
                continue;
            }
            try {
                if (m.reset(sb).matches()) {
                    return Filter.Result.DENY;
                }
            } finally {
                m.reset("");
            }
        }
        return Filter.Result.NEUTRAL;
    }

    @Unique
    private static String[] sunlit$getPhrases(List<? extends String> phrasesList) {
        Object cachedList = sunlit$cachedPhraseList;
        String[] cached = sunlit$cachedPhrases;
        int size = phrasesList.size();
        if (cachedList == phrasesList && cached.length == size) {
            return cached;
        }
        String[] next = phrasesList.toArray(new String[size]);
        sunlit$cachedPhrases = next;
        sunlit$cachedPhraseList = phrasesList;
        return next;
    }

    @Unique
    private static Pattern[] sunlit$getPatterns(List<? extends String> regexList) {
        String[] saved = sunlit$cachedRegexes;
        Pattern[] patterns = sunlit$cachedPatterns;
        int size = regexList.size();
        if (saved != null && patterns != null && saved.length == size) {
            boolean same = true;
            for (int i = 0; i < size; i++) {
                if (!Objects.equals(saved[i], regexList.get(i))) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return patterns;
            }
        }
        String[] nextSaved = new String[size];
        Pattern[] nextPatterns = new Pattern[size];
        for (int i = 0; i < size; i++) {
            String r = regexList.get(i);
            nextSaved[i] = r;
            if (r != null) {
                nextPatterns[i] = Pattern.compile(r);
            }
        }
        sunlit$cachedRegexes = nextSaved;
        sunlit$cachedPatterns = nextPatterns;
        return nextPatterns;
    }
}
