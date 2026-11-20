package dev.sunlit.bettersunlit.client;

import com.hrznstudio.emojiful.Constants;
import com.hrznstudio.emojiful.api.Emoji;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Inverted 2-char trigger index over {@link Constants#EMOJI_LIST} so
 * {@code EmojiFontRenderer.getEmojiFormattedString(String)} only tests the 0-3 emojis whose
 * trigger anchor actually appears in the clean string instead of scanning all 2,016 entries.
 */
public final class EmojiCandidatePerf {
    private static volatile BucketIndex cachedBuckets;

    private static final class ThreadScratch {
        int[] stamps = new int[0];
        int currentGen = 1;
        final ArrayList<Emoji> out = new ArrayList<>(8);
    }

    private static final ThreadLocal<ThreadScratch> SCRATCH = ThreadLocal.withInitial(ThreadScratch::new);

    private static final class BucketIndex {
        final List<Emoji> listRef;
        final int size;
        final Emoji headRef;
        final Emoji tailRef;
        final int count;
        final HashMap<Integer, Emoji[]> buckets;
        final List<Emoji> alwaysCheck;

        BucketIndex(
                List<Emoji> listRef,
                int size,
                Emoji headRef,
                Emoji tailRef,
                int count,
                HashMap<Integer, Emoji[]> buckets,
                List<Emoji> alwaysCheck) {
            this.listRef = listRef;
            this.size = size;
            this.headRef = headRef;
            this.tailRef = tailRef;
            this.count = count;
            this.buckets = buckets;
            this.alwaysCheck = alwaysCheck;
        }

        boolean matches(List<Emoji> list) {
            int n = list.size();
            return this.listRef == list
                    && this.size == n
                    && (n == 0 || (this.headRef == list.get(0) && this.tailRef == list.get(n - 1)));
        }
    }

    private EmojiCandidatePerf() {
    }

    public static void prewarm() {
        getOrBuild();
    }

    private static BucketIndex getOrBuild() {
        List<Emoji> list = Constants.EMOJI_LIST;
        if (list == null || list.isEmpty()) {
            return null;
        }
        BucketIndex idx = cachedBuckets;
        if (idx != null && idx.matches(list)) {
            return idx;
        }
        synchronized (EmojiCandidatePerf.class) {
            idx = cachedBuckets;
            if (idx != null && idx.matches(list)) {
                return idx;
            }
            idx = build(list);
            cachedBuckets = idx;
            return idx;
        }
    }

    private static BucketIndex build(List<Emoji> list) {
        int n = list.size();
        Emoji head = list.get(0);
        Emoji tail = list.get(n - 1);
        HashMap<Integer, ArrayList<Emoji>> tmp = new HashMap<>(1024);
        ArrayList<Emoji> always = new ArrayList<>(16);

        for (int i = 0; i < n; i++) {
            Emoji emoji = list.get(i);
            if (emoji == null) {
                continue;
            }
            if (!EmojiRenderPerf.isStandardEmojiClass(emoji) || emoji.strings == null || emoji.strings.isEmpty()) {
                always.add(emoji);
                continue;
            }
            boolean allIndexed = true;
            ArrayList<Integer> keysForEmoji = new ArrayList<>(emoji.strings.size());
            for (int t = 0, tn = emoji.strings.size(); t < tn; t++) {
                String trig = emoji.strings.get(t);
                int anchor = extractAnchor(trig);
                if (anchor < 0) {
                    allIndexed = false;
                    break;
                }
                keysForEmoji.add(anchor);
            }
            if (!allIndexed) {
                always.add(emoji);
            } else {
                for (int k = 0, kn = keysForEmoji.size(); k < kn; k++) {
                    tmp.computeIfAbsent(keysForEmoji.get(k), ignored -> new ArrayList<>(4)).add(emoji);
                }
            }
        }

        HashMap<Integer, Emoji[]> compact = new HashMap<>(tmp.size() * 2 + 16);
        for (var e : tmp.entrySet()) {
            ArrayList<Emoji> bucket = e.getValue();
            compact.put(e.getKey(), bucket.toArray(new Emoji[0]));
        }
        return new BucketIndex(list, n, head, tail, n, compact, List.copyOf(always));
    }

    private static int extractAnchor(String trig) {
        if (trig == null || trig.length() < 2) {
            return -1;
        }
        // Reject patterns with regex meta characters where literal 2-char slice might be optional.
        for (int i = 0, len = trig.length(); i < len; i++) {
            char c = trig.charAt(i);
            if (c == '\\' || c == '[' || c == ']' || c == '(' || c == ')' || c == '{' || c == '}'
                    || c == '*' || c == '+' || c == '?' || c == '|' || c == '^' || c == '$' || c == '.') {
                return -1;
            }
        }
        int offset = (trig.charAt(0) == ':' && trig.length() >= 3) ? 1 : 0;
        char c0 = Character.toLowerCase(trig.charAt(offset));
        char c1 = Character.toLowerCase(trig.charAt(offset + 1));
        return (c0 << 16) | c1;
    }

    public static List<Emoji> candidatesForClean(String clean) {
        if (clean == null || clean.isEmpty() || !EmojiRenderPerf.mightContainEmoji(clean)) {
            return Collections.emptyList();
        }
        BucketIndex idx = getOrBuild();
        if (idx == null) {
            List<Emoji> raw = Constants.EMOJI_LIST;
            return raw == null ? Collections.emptyList() : raw;
        }
        int len = clean.length();
        if (len < 2 && idx.alwaysCheck.isEmpty()) {
            return Collections.emptyList();
        }
        ThreadScratch scratch = SCRATCH.get();
        if (scratch.stamps.length < idx.count) {
            scratch.stamps = new int[idx.count];
        }
        int gen = ++scratch.currentGen;
        if (gen == 0) {
            java.util.Arrays.fill(scratch.stamps, 0);
            gen = scratch.currentGen = 1;
        }
        ArrayList<Emoji> out = scratch.out;
        out.clear();
        if (!idx.alwaysCheck.isEmpty()) {
            out.addAll(idx.alwaysCheck);
        }
        HashMap<Integer, Emoji[]> buckets = idx.buckets;
        for (int i = 0, limit = len - 1; i < limit; i++) {
            char a = Character.toLowerCase(clean.charAt(i));
            char b = Character.toLowerCase(clean.charAt(i + 1));
            Emoji[] hits = buckets.get((a << 16) | b);
            if (hits == null) {
                continue;
            }
            for (int j = 0; j < hits.length; j++) {
                Emoji candidate = hits[j];
                // IdentityHashMap lookup wasn't needed: System.identityHashCode or scan <= 4 items in out is O(1).
                if (!containsRef(out, candidate)) {
                    out.add(candidate);
                }
            }
        }
        return out.isEmpty() ? Collections.emptyList() : out;
    }

    private static boolean containsRef(ArrayList<Emoji> list, Emoji target) {
        for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i) == target) {
                return true;
            }
        }
        return false;
    }
}
