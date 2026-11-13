package dev.sunlit.bettersunlit.client;

import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Allocation-free biome matching and direction-table helper for AmbientSounds'
 * {@code BiomeArea.checkBiome} and {@code AirPocketScanner.scan}.
 * Preserves original substring-wildcard semantics {@code ".*" + def.replace("*", ".*") + ".*"}
 * verified from AmbientSounds bytecode without per-call Pattern compilation or string concatenation.
 */
public final class AmbientSoundsClientPerf {
    public static final Direction[] DIRECTIONS = Direction.values();
    private static final int MAX_LOC_CACHE = 4096;
    private static final int MAX_RULE_CACHE = 4096;
    private static final String[] EMPTY_PARTS = new String[0];

    private static final ConcurrentHashMap<ResourceLocation, String> LOC_STRING_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, BiomeRule> RULE_CACHE = new ConcurrentHashMap<>();

    private AmbientSoundsClientPerf() {}

    public static final class BiomeRule {
        final boolean isTag;
        final String plainLiteral;
        final String[] globParts;
        final Pattern fallbackRegex;

        BiomeRule(boolean isTag, String plainLiteral, String[] globParts, Pattern fallbackRegex) {
            this.isTag = isTag;
            this.plainLiteral = plainLiteral;
            this.globParts = globParts;
            this.fallbackRegex = fallbackRegex;
        }

        public boolean matchesText(String text) {
            if (plainLiteral != null) {
                return text.contains(plainLiteral);
            }
            if (globParts != null) {
                return matchesOrderedParts(text, globParts);
            }
            return fallbackRegex.matcher(text).matches();
        }
    }

    public static boolean checkBiome(Holder<Biome> biome, ResourceLocation location, String[] definitions) {
        if (definitions == null || definitions.length == 0) return false;
        String cachedLoc = null;
        for (int i = 0; i < definitions.length; i++) {
            String def = definitions[i];
            BiomeRule rule = getRule(def);
            if (rule.isTag) {
                Iterator<TagKey<Biome>> it = biome.m_203616_().iterator();
                while (it.hasNext()) {
                    if (rule.matchesText(cachedLocationString(it.next().f_203868_()))) {
                        return true;
                    }
                }
            } else {
                if (cachedLoc == null) {
                    cachedLoc = cachedLocationString(location);
                }
                if (rule.matchesText(cachedLoc)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String cachedLocationString(ResourceLocation loc) {
        String s = LOC_STRING_CACHE.get(loc);
        if (s != null) return s;
        s = loc.toString();
        if (LOC_STRING_CACHE.size() < MAX_LOC_CACHE) {
            String prev = LOC_STRING_CACHE.putIfAbsent(loc, s);
            if (prev != null) s = prev;
        }
        return s;
    }

    public static BiomeRule getRule(String rawDef) {
        BiomeRule cached = RULE_CACHE.get(rawDef);
        if (cached != null) return cached;
        BiomeRule built = compileRule(rawDef);
        if (RULE_CACHE.size() < MAX_RULE_CACHE) {
            BiomeRule prev = RULE_CACHE.putIfAbsent(rawDef, built);
            if (prev != null) built = prev;
        }
        return built;
    }

    private static BiomeRule compileRule(String rawDef) {
        boolean isTag = rawDef.startsWith("#");
        String body = isTag ? rawDef.substring(1) : rawDef;
        boolean hasStar = false;
        boolean hasOtherRegex = false;
        for (int i = 0, len = body.length(); i < len; i++) {
            char c = body.charAt(i);
            if (c == '*') {
                hasStar = true;
            } else if (isNonStarRegexMeta(c)) {
                hasOtherRegex = true;
                break;
            }
        }
        if (!hasStar && !hasOtherRegex) {
            return new BiomeRule(isTag, body, null, null);
        }
        if (!hasOtherRegex) {
            return new BiomeRule(isTag, null, splitNonEmptyStars(body), null);
        }
        Pattern regex = Pattern.compile(".*" + body.replace("*", ".*") + ".*");
        return new BiomeRule(isTag, null, null, regex);
    }

    private static boolean isNonStarRegexMeta(char c) {
        return switch (c) {
            case '\\', '.', '[', ']', '{', '}', '(', ')', '+', '?', '^', '$', '|' -> true;
            default -> false;
        };
    }

    private static String[] splitNonEmptyStars(String body) {
        ArrayList<String> parts = new ArrayList<>(4);
        int start = 0;
        int len = body.length();
        while (start < len) {
            int star = body.indexOf('*', start);
            if (star < 0) {
                parts.add(body.substring(start));
                break;
            }
            if (star > start) {
                parts.add(body.substring(start, star));
            }
            start = star + 1;
        }
        return parts.isEmpty() ? EMPTY_PARTS : parts.toArray(new String[0]);
    }

    private static boolean matchesOrderedParts(String text, String[] parts) {
        int pos = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int idx = text.indexOf(part, pos);
            if (idx < 0) return false;
            pos = idx + part.length();
        }
        return true;
    }

    public static void clearCachesForTests() {
        LOC_STRING_CACHE.clear();
        RULE_CACHE.clear();
    }
}
