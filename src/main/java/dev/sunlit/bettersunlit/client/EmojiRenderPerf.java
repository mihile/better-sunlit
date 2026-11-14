package dev.sunlit.bettersunlit.client;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.hrznstudio.emojiful.Constants;
import com.hrznstudio.emojiful.api.Emoji;
import com.hrznstudio.emojiful.platform.Services;
import com.hrznstudio.emojiful.render.EmojiFontRenderer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import org.apache.commons.lang3.tuple.Pair;

import java.util.BitSet;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.IdentityHashMap;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Bounded cache factory, reusable {@link FormattedCharSequence} buffer, and zero-alloc
 * 2-char pre-filter index for Emojiful's {@link EmojiFontRenderer}.
 */
public final class EmojiRenderPerf {

    public static final int MAX_CACHE_SIZE = 4096;

    private static final EmojiIndex EMPTY_INDEX = new EmojiIndex(null, 0, null, null, false, true);
    private static final EmojiIndex FALLBACK_ALL = new EmojiIndex(null, -1, null, null, true, false);

    private static volatile EmojiIndex cachedIndex = EMPTY_INDEX;
    private static volatile LoadingCache<String, Pair<String, HashMap<Integer, Emoji>>> recentCache;

    private static final ThreadLocal<SbLease> SB_LEASE = ThreadLocal.withInitial(SbLease::new);

    private EmojiRenderPerf() {
    }

    public static LoadingCache<String, Pair<String, HashMap<Integer, Emoji>>> createBoundedCache() {
        LoadingCache<String, Pair<String, HashMap<Integer, Emoji>>> cache = CacheBuilder.newBuilder()
                .expireAfterAccess(60, TimeUnit.SECONDS)
                .maximumSize(MAX_CACHE_SIZE)
                .build(new CacheLoader<>() {
                    @Override
                    public Pair<String, HashMap<Integer, Emoji>> load(String key) {
                        return EmojiFontRenderer.getEmojiFormattedString(key);
                    }
                });
        recentCache = cache;
        return cache;
    }

    public static boolean isRenderEmojiEnabled() {
        return Services.CONFIG != null && Services.CONFIG.renderEmoji();
    }

    public static boolean mightContainEmoji(CharSequence text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        return getOrBuildIndex().mightContain(text);
    }

    /**
     * Collects characters from {@code seq} into a {@link ThreadLocal} {@link StringBuilder}.
     * Returns {@code null} when the sequence cannot possibly contain any configured emoji trigger,
     * allowing callers to skip {@link String} allocation and cache/regex evaluation completely.
     */
    public static String extractIfMightContainEmoji(FormattedCharSequence seq) {
        if (seq == null) {
            return null;
        }
        SbLease lease = SB_LEASE.get();
        if (lease.busy) {
            StringBuilder fallback = new StringBuilder();
            seq.m_13731_((pos, style, cp) -> {
                fallback.append((char) cp);
                return true;
            });
            return mightContainEmoji(fallback) ? fallback.toString() : null;
        }
        lease.busy = true;
        try {
            seq.m_13731_(lease.sink);
            if (lease.sb.length() == 0 || !mightContainEmoji(lease.sb)) {
                return null;
            }
            return lease.sb.toString();
        } finally {
            lease.sb.setLength(0);
            lease.busy = false;
        }
    }

    public static Pair<String, HashMap<Integer, Emoji>> getCachedOrLoad(
            LoadingCache<String, Pair<String, HashMap<Integer, Emoji>>> cache,
            String key) {
        try {
            return cache.get(key);
        } catch (ExecutionException e) {
            return null;
        }
    }

    public static void invalidateIndex() {
        cachedIndex = EMPTY_INDEX;
        invalidateRecentStrings();
    }

    private static void invalidateRecentStrings() {
        LoadingCache<?, ?> cache = recentCache;
        if (cache != null) cache.invalidateAll();
    }

    static EmojiIndex buildIndexFromList(List<Emoji> emojis) {
        if (emojis == null || emojis.isEmpty()) {
            return EMPTY_INDEX;
        }
        int size = emojis.size();
        Emoji head = emojis.get(0);
        Emoji tail = emojis.get(size - 1);
        EmojiIndex idx = new EmojiIndex(emojis, size, head, tail, false, false);
        for (int i = 0; i < size; i++) {
            Emoji emoji = emojis.get(i);
            if (emoji == null) {
                continue;
            }
            if (!indexEmoji(idx, emoji)) {
                idx.unindexableFallback = true;
                break;
            }
        }
        return idx;
    }

    private static EmojiIndex getOrBuildIndex() {
        List<Emoji> list = Constants.EMOJI_LIST;
        if (list == null || list.isEmpty()) {
            if (cachedIndex != EMPTY_INDEX) invalidateIndex();
            return EMPTY_INDEX;
        }
        try {
            int size = list.size();
            Emoji head = list.get(0);
            Emoji tail = list.get(size - 1);
            EmojiIndex cur = cachedIndex;
            if (cur.matches(list, size, head, tail)) {
                return cur;
            }
            cur.resetChangedRegexes(list);
            invalidateRecentStrings();
            EmojiIndex rebuilt = buildIndexFromList(list);
            cachedIndex = rebuilt;
            return rebuilt;
        } catch (IndexOutOfBoundsException | ConcurrentModificationException e) {
            // Concurrent async population in progress: conservatively let Emojiful evaluate normally.
            return FALLBACK_ALL;
        }
    }

    /**
     * Replicates {@link Emoji#getRegexString()} without invoking {@code EmojiUtil} (which extends
     * {@code RenderType} and triggers client GL/registry {@code <clinit>}).
     */
    private static String resolveRegexString(Emoji emoji) {
        if (emoji.getClass() != Emoji.class) {
            return emoji.getRegexString();
        }
        List<String> strings = emoji.strings;
        if (strings == null || strings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(32);
        for (int sIdx = 0; sIdx < strings.size(); sIdx++) {
            String str = strings.get(sIdx);
            if (str == null || str.isEmpty()) {
                return null;
            }
            if (sIdx > 0) {
                sb.append('|');
            }
            char first = Character.toLowerCase(str.charAt(0));
            char last = Character.toLowerCase(str.charAt(str.length() - 1));
            if (first >= 'a' && first <= 'z') {
                sb.append("\\b");
            }
            for (int i = 0; i < str.length(); i++) {
                char ch = str.charAt(i);
                if (ch == '(' || ch == ')' || ch == '|' || ch == '*') {
                    sb.append('\\');
                }
                sb.append(ch);
            }
            if (last >= 'a' && last <= 'z') {
                sb.append("\\b");
            }
        }
        return sb.toString();
    }

    private static boolean indexEmoji(EmojiIndex idx, Emoji emoji) {
        String regex = resolveRegexString(emoji);
        if (regex == null || regex.isEmpty()) {
            // Emoji with no regex strings cannot match anything; no fallback needed.
            if (emoji.strings == null || emoji.strings.isEmpty()) {
                return true;
            }
            return false;
        }

        StringBuilder literalBuf = new StringBuilder(32);
        int len = regex.length();
        boolean escape = false;
        boolean emptyAlt = false;
        for (int i = 0; i < len; i++) {
            char c = regex.charAt(i);
            if (escape) {
                escape = false;
                if (c == '(' || c == ')' || c == '|' || c == '*') {
                    literalBuf.append(c);
                } else if (c == 'b') {
                    // Word boundary anchor \b only restricts matches; dropping it keeps the pre-filter a sound superset.
                } else {
                    // Unfamiliar regex escape (\d, \w, etc.) -> fall back conservatively.
                    return false;
                }
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '|') {
                if (literalBuf.length() == 0) {
                    emptyAlt = true;
                    break;
                }
                if (!idx.addLiteral(literalBuf)) {
                    return false;
                }
                literalBuf.setLength(0);
                continue;
            }
            if (c == '.' || c == '+' || c == '?' || c == '^' || c == '$'
                    || c == '[' || c == ']' || c == '{' || c == '}'
                    || c == '(' || c == ')' || c == '*') {
                return false;
            }
            literalBuf.append(c);
        }
        if (escape || emptyAlt || literalBuf.length() == 0) {
            return false;
        }
        return idx.addLiteral(literalBuf);
    }

    static boolean isFormattingCodeChar(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F')
                || (c >= 'k' && c <= 'o')
                || (c >= 'K' && c <= 'O')
                || c == 'r'
                || c == 'R';
    }

    static final class EmojiIndex {
        private final List<Emoji> listRef;
        private final int listSize;
        private final Emoji headRef;
        private final Emoji tailRef;
        private final boolean empty;
        private final Emoji[] sources;
        private final String[][] triggers;
        boolean unindexableFallback;

        // 256 * 4 = 1024 longs = 8 KB L1-friendly exact ASCII/Latin-1 (c0, c1) bigram bitset.
        private final long[] asciiPairs = new long[1024];
        private final long[] singleAscii = new long[4];
        private BitSet singleUnicode;
        private long[] nonAsciiPairFilter;
        private boolean hasNonAsciiPairs;
        private boolean hasAnySingle;

        EmojiIndex(
                List<Emoji> listRef,
                int listSize,
                Emoji headRef,
                Emoji tailRef,
                boolean unindexableFallback,
                boolean empty) {
            this.listRef = listRef;
            this.listSize = listSize;
            this.headRef = headRef;
            this.tailRef = tailRef;
            this.unindexableFallback = unindexableFallback;
            this.empty = empty;
            if (listRef == null) {
                sources = new Emoji[0]; triggers = new String[0][];
            } else {
                sources = listRef.toArray(new Emoji[0]);
                triggers = new String[sources.length][];
                for (int i = 0; i < sources.length; i++) {
                    List<String> strings = sources[i] == null ? null : sources[i].strings;
                    triggers[i] = strings == null ? null : strings.toArray(new String[0]);
                }
            }
        }

        boolean matches(List<Emoji> list, int size, Emoji head, Emoji tail) {
            return !empty
                    && listRef == list
                    && listSize == size
                    && headRef == head
                    && tailRef == tail
                    && matchesContents(list);
        }

        private boolean matchesContents(List<Emoji> list) {
            if (list.size() != sources.length) return false;
            for (int i = 0; i < sources.length; i++) {
                Emoji emoji = list.get(i);
                if (emoji != sources[i] || !sameTriggers(triggers[i], emoji == null ? null : emoji.strings)) return false;
            }
            return true;
        }

        private static boolean sameTriggers(String[] saved, List<String> live) {
            if (saved == null || live == null) return saved == null && live == null;
            if (saved.length != live.size()) return false;
            for (int i = 0; i < saved.length; i++) if (!Objects.equals(saved[i], live.get(i))) return false;
            return true;
        }

        void resetChangedRegexes(List<Emoji> list) {
            // Emoji caches its regex independently. Refresh only base Emoji instances whose
            // trigger contents changed; custom subclasses retain their own fallback semantics.
            IdentityHashMap<Emoji, String[]> saved = new IdentityHashMap<>();
            for (int i = 0; i < sources.length; i++) saved.put(sources[i], triggers[i]);
            for (int i = 0; i < list.size(); i++) {
                Emoji emoji = list.get(i);
                if (emoji != null && saved.containsKey(emoji) && emoji.getClass() == Emoji.class
                        && !sameTriggers(saved.get(emoji), emoji.strings)) {
                    NativeRegexCache.clear(emoji);
                }
            }
        }

        boolean addLiteral(CharSequence lit) {
            int len = lit.length();
            if (len == 0) {
                return false;
            }
            for (int i = 0; i < len; i++) {
                if (lit.charAt(i) == '\u00a7') {
                    return false;
                }
            }
            char c0 = lit.charAt(0);
            if (len == 1) {
                hasAnySingle = true;
                if (c0 < 256) {
                    singleAscii[c0 >>> 6] |= 1L << (c0 & 63);
                } else {
                    if (singleUnicode == null) {
                        singleUnicode = new BitSet();
                    }
                    singleUnicode.set(c0);
                }
                return true;
            }
            char c1 = lit.charAt(1);
            if (c0 < 256 && c1 < 256) {
                asciiPairs[(c0 << 2) | (c1 >>> 6)] |= 1L << (c1 & 63);
            } else {
                if (nonAsciiPairFilter == null) {
                    nonAsciiPairFilter = new long[64];
                }
                int h = ((c0 * 31) ^ c1) & 4095;
                nonAsciiPairFilter[h >>> 6] |= 1L << (h & 63);
                hasNonAsciiPairs = true;
            }
            return true;
        }

        boolean mightContain(CharSequence s) {
            if (empty) {
                return false;
            }
            if (unindexableFallback) {
                return true;
            }
            int len = s.length();
            int prev = -1;
            for (int i = 0; i < len; i++) {
                char c = s.charAt(i);
                if (c == '\u00a7' && i + 1 < len && isFormattingCodeChar(s.charAt(i + 1))) {
                    i++;
                    continue;
                }
                if (hasAnySingle) {
                    if (c < 256) {
                        if ((singleAscii[c >>> 6] & (1L << (c & 63))) != 0L) {
                            return true;
                        }
                    } else if (singleUnicode != null && singleUnicode.get(c)) {
                        return true;
                    }
                }
                if (prev >= 0) {
                    if (prev < 256 && c < 256) {
                        if ((asciiPairs[(prev << 2) | (c >>> 6)] & (1L << (c & 63))) != 0L) {
                            return true;
                        }
                    } else if (hasNonAsciiPairs) {
                        int h = ((prev * 31) ^ c) & 4095;
                        if ((nonAsciiPairFilter[h >>> 6] & (1L << (h & 63))) != 0L) {
                            return true;
                        }
                    }
                }
                prev = c;
            }
            return false;
        }
    }

    private static final class NativeRegexCache {
        private static final Field REGEX = field("regex");
        private static final Field PATTERN = field("regexPattern");
        private static final Field SHORT = field("shortString");
        private static Field field(String name) {
            try {
                Field field = Emoji.class.getDeclaredField(name); field.setAccessible(true); return field;
            } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
        }
        static void clear(Emoji emoji) {
            try { REGEX.set(emoji, null); PATTERN.set(emoji, null); SHORT.set(emoji, null); }
            catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
    }

    private static final class SbLease {
        final StringBuilder sb = new StringBuilder(128);
        final FormattedCharSink sink = (pos, style, cp) -> {
            sb.append((char) cp);
            return true;
        };
        boolean busy;
    }
}
