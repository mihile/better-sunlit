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
    public static final int UP_MASK = 1 << Direction.UP.ordinal();
    public static final int ALL_DIRS_MASK = (1 << DIRECTIONS.length) - 1;
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

    public static boolean canExpandOutward(int dirOrdinal, int px, int py, int pz, int ox, int oy, int oz) {
        return switch (dirOrdinal) {
            case 0 -> py - 1 < oy; // DOWN (-Y)
            case 1 -> py + 1 > oy; // UP (+Y)
            case 2 -> pz - 1 < oz; // NORTH (-Z)
            case 3 -> pz + 1 > oz; // SOUTH (+Z)
            case 4 -> px - 1 < ox; // WEST (-X)
            case 5 -> px + 1 > ox; // EAST (+X)
            default -> false;
        };
    }

    public static final class WaveQueue {
        private long[] packedPos;
        private byte[] dirMasks;
        private int[] hashTable;
        private int mask;
        private int resizeThreshold;
        private int size;

        public WaveQueue(int initialCapacity) {
            int cap = it.unimi.dsi.fastutil.HashCommon.nextPowerOfTwo(Math.max(16, initialCapacity));
            this.packedPos = new long[cap];
            this.dirMasks = new byte[cap];
            int tableCap = Math.max(32, cap << 1);
            this.hashTable = new int[tableCap];
            this.mask = tableCap - 1;
            this.resizeThreshold = (tableCap * 3) >>> 2;
        }

        public void clear() {
            if (this.size > 0) {
                java.util.Arrays.fill(this.hashTable, 0);
                this.size = 0;
            }
        }

        public int size() {
            return this.size;
        }

        public long packedPosAt(int index) {
            return this.packedPos[index];
        }

        public int dirMaskAt(int index) {
            return this.dirMasks[index] & 0xFF;
        }

        public void addSeed(long pos) {
            insertOrMerge(pos, ALL_DIRS_MASK);
        }

        public boolean addDirection(long pos, int dirBit) {
            return insertOrMerge(pos, dirBit);
        }

        private boolean insertOrMerge(long pos, int dirBit) {
            int slot = (int) it.unimi.dsi.fastutil.HashCommon.mix(pos) & this.mask;
            while (true) {
                int entry = this.hashTable[slot];
                if (entry == 0) {
                    break;
                }
                int idx = entry - 1;
                if (this.packedPos[idx] == pos) {
                    this.dirMasks[idx] = (byte) (this.dirMasks[idx] | dirBit);
                    return false;
                }
                slot = (slot + 1) & this.mask;
            }
            if (this.size >= this.resizeThreshold || this.size == this.packedPos.length) {
                grow();
                slot = (int) it.unimi.dsi.fastutil.HashCommon.mix(pos) & this.mask;
                while (this.hashTable[slot] != 0) {
                    slot = (slot + 1) & this.mask;
                }
            }
            int idx = this.size++;
            this.packedPos[idx] = pos;
            this.dirMasks[idx] = (byte) dirBit;
            this.hashTable[slot] = idx + 1;
            return true;
        }

        private void grow() {
            if (this.size == this.packedPos.length) {
                int newCap = this.packedPos.length << 1;
                this.packedPos = java.util.Arrays.copyOf(this.packedPos, newCap);
                this.dirMasks = java.util.Arrays.copyOf(this.dirMasks, newCap);
            }
            if (this.size >= this.resizeThreshold) {
                int newTableCap = this.hashTable.length << 1;
                int[] newTable = new int[newTableCap];
                int newMask = newTableCap - 1;
                for (int i = 0; i < this.size; i++) {
                    int s = (int) it.unimi.dsi.fastutil.HashCommon.mix(this.packedPos[i]) & newMask;
                    while (newTable[s] != 0) {
                        s = (s + 1) & newMask;
                    }
                    newTable[s] = i + 1;
                }
                this.hashTable = newTable;
                this.mask = newMask;
                this.resizeThreshold = (newTableCap * 3) >>> 2;
            }
        }
    }

    public static void clearCachesForTests() {
        LOC_STRING_CACHE.clear();
        RULE_CACHE.clear();
    }
}
