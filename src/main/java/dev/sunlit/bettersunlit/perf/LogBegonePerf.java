package dev.sunlit.bettersunlit.perf;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/** Immutable filter snapshot with reusable matchers and content-aware invalidation. */
public final class LogBegonePerf {
    private static volatile FilterSnapshot snapshot;
    private static final ThreadLocal<MatcherCache> MATCHERS = ThreadLocal.withInitial(MatcherCache::new);
    private LogBegonePerf() {}

    public static void invalidate() { snapshot = null; }

    public static void onConfigEvent(ModConfigEvent event) {
        if ("logbegone".equals(event.getConfig().getModId())) invalidate();
    }

    public static boolean shouldFilter(String message, List<? extends String> phrases, List<? extends String> regexes) {
        if (message == null) return true;
        FilterSnapshot snap = snapshot;
        if (snap == null || !snap.matches(phrases, regexes)) {
            snap = new FilterSnapshot(copy(phrases), copy(regexes));
            snapshot = snap;
        }
        for (String phrase : snap.phrases) if (phrase != null && message.contains(phrase)) return true;
        Matcher[] matchers = MATCHERS.get().forSnapshot(snap);
        for (Matcher matcher : matchers) {
            if (matcher == null) continue;
            try {
                if (matcher.reset(message).matches()) return true;
            } finally {
                // Matcher otherwise retains the last log message for the life of the thread.
                matcher.reset("");
            }
        }
        return false;
    }

    private static String[] copy(List<? extends String> source) {
        return source == null ? new String[0] : source.toArray(new String[0]);
    }

    private static boolean sameContents(String[] saved, List<? extends String> live) {
        if (live == null) return saved.length == 0;
        if (live.size() != saved.length) return false;
        for (int i = 0; i < saved.length; i++) if (!Objects.equals(saved[i], live.get(i))) return false;
        return true;
    }

    private static final class FilterSnapshot {
        final String[] phrases, regexes;
        final Pattern[] patterns;
        FilterSnapshot(String[] phrases, String[] regexes) {
            this.phrases = phrases; this.regexes = regexes;
            patterns = new Pattern[regexes.length];
            for (int i = 0; i < patterns.length; i++) if (regexes[i] != null) patterns[i] = Pattern.compile(regexes[i]);
        }
        boolean matches(List<? extends String> phrases, List<? extends String> regexes) {
            return sameContents(this.phrases, phrases) && sameContents(this.regexes, regexes);
        }
    }

    private static final class MatcherCache {
        private FilterSnapshot owner;
        private Matcher[] matchers = new Matcher[0];
        Matcher[] forSnapshot(FilterSnapshot snap) {
            if (owner != snap) {
                Matcher[] next = new Matcher[snap.patterns.length];
                for (int i = 0; i < next.length; i++) if (snap.patterns[i] != null) next[i] = snap.patterns[i].matcher("");
                matchers = next; owner = snap;
            }
            return matchers;
        }
    }
}
