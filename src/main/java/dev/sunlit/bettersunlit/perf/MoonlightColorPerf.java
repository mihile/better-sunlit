package dev.sunlit.bettersunlit.perf;

import java.util.List;

/**
 * Zero-allocation color prefix/suffix matcher for Moonlight's {@code BlocksColorInternal}.
 */
public final class MoonlightColorPerf {
    private MoonlightColorPerf() {
    }

    /**
     * Finds the first color key matching {@code path} as either {@code key + "_*"} or {@code "*_" + key},
     * giving suffix priority when both match for the same key (matching Moonlight's original semantics).
     *
     * @return {@code -1} if no key matches; otherwise {@code (index << 1)} for prefix match or
     *         {@code (index << 1) | 1} for suffix match.
     */
    public static int findColorMatch(String path, List<String> colorKeys) {
        if (path == null || colorKeys == null) {
            return -1;
        }
        int plen = path.length();
        for (int i = 0, n = colorKeys.size(); i < n; i++) {
            String key = colorKeys.get(i);
            int clen = key.length();
            if (plen > clen) {
                if (path.charAt(plen - clen - 1) == '_' && path.regionMatches(plen - clen, key, 0, clen)) {
                    return (i << 1) | 1;
                }
                if (path.charAt(clen) == '_' && path.regionMatches(0, key, 0, clen)) {
                    return i << 1;
                }
            }
        }
        return -1;
    }

    public static String extractSubpath(String path, String key, int encodedMatch) {
        if ((encodedMatch & 1) != 0) {
            return path.substring(0, path.length() - key.length() - 1);
        }
        return path.substring(key.length() + 1);
    }
}
