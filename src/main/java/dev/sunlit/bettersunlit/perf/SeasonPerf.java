package dev.sunlit.bettersunlit.perf;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.init.ModConfig;
import sereneseasons.init.ModTags;
import sereneseasons.season.SeasonTime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.RandomAccess;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Common allocation-free helpers for SereneSeasons whitelist, season-time, and temperature checks. */
public final class SeasonPerf {
    private static final int RING_SIZE = 64;
    private static final int RING_SHIFT = 26;
    private static final AtomicReferenceArray<SeasonTime> SEASON_TIME_RING = new AtomicReferenceArray<>(RING_SIZE);
    private static final MethodHandle BIOME_GET_TEMPERATURE = resolveBiomeGetTemperature();

    private SeasonPerf() {}

    private static MethodHandle resolveBiomeGetTemperature() {
        try {
            return MethodHandles.publicLookup().findVirtual(
                    Biome.class, "m_47505_", MethodType.methodType(float.class, BlockPos.class));
        } catch (IllegalAccessException | NoSuchMethodException publicMiss) {
            try {
                Method m = Biome.class.getDeclaredMethod("m_47505_", BlockPos.class);
                m.setAccessible(true);
                return MethodHandles.lookup().unreflect(m);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    public static float biomeTemperature(Biome biome, BlockPos pos) {
        try {
            return (float) BIOME_GET_TEMPERATURE.invokeExact(biome, pos);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Failed calling Biome.m_47505_", t);
        }
    }

    /** Allocation-free equivalent of checking whether {@code dimension.location().toString()} appears in {@code list}. */
    public static boolean isDimensionWhitelisted(List<String> list, ResourceKey<Level> dimension) {
        ResourceLocation loc = dimension.m_135782_();
        if (list == null) return false;
        if (list instanceof RandomAccess) {
            for (int i = 0, n = list.size(); i < n; i++) {
                if (matchesLocation(loc, list.get(i))) return true;
            }
            return false;
        }
        for (String entry : list) {
            if (matchesLocation(loc, entry)) return true;
        }
        return false;
    }

    public static boolean matchesLocation(ResourceLocation loc, String candidate) {
        if (candidate == null) return false;
        String ns = loc.m_135827_();
        String path = loc.m_135815_();
        int nsLen = ns.length();
        int pathLen = path.length();
        if (candidate.length() != nsLen + 1 + pathLen) return false;
        if (candidate.charAt(nsLen) != ':') return false;
        return candidate.regionMatches(0, ns, 0, nsLen)
                && candidate.regionMatches(nsLen + 1, path, 0, pathLen);
    }

    /** Returns a cached SeasonTime for {@code cycleTicks} without per-call heap allocations. */
    public static SeasonTime seasonState(int cycleTicks) {
        if (cycleTicks == 0) return SeasonTime.ZERO;
        if (cycleTicks < 0) return new SeasonTime(cycleTicks); // Preserves IllegalArgumentException.
        int slot = (cycleTicks * 0x9E3779B9) >>> RING_SHIFT;
        SeasonTime cached = SEASON_TIME_RING.get(slot);
        if (cached != null && cached.time == cycleTicks) {
            return cached;
        }
        SeasonTime created = new SeasonTime(cycleTicks);
        SEASON_TIME_RING.set(slot, created);
        return created;
    }

    /**
     * Exact equivalent of {@code SeasonHooks.getBiomeTemperature(Level, Holder, BlockPos)}
     * without constructing a redundant second {@link SeasonTime} or re-checking {@code BLACKLISTED_BIOMES}.
     */
    public static float getBiomeTemperature(Level level, Holder<Biome> biome, BlockPos pos) {
        Biome biomeValue = biome.m_203334_();
        if (!ModConfig.seasons.isDimensionWhitelisted(level.m_46472_())
                || biome.m_203656_(ModTags.Biomes.BLACKLISTED_BIOMES)) {
            return biomeTemperature(biomeValue, pos);
        }
        Season.SubSeason subSeason = SeasonHelper.getSeasonState(level).getSubSeason();
        float temp = biomeTemperature(biomeValue, pos);
        if (!biome.m_203656_(ModTags.Biomes.TROPICAL_BIOMES) && biomeValue.m_47554_() <= 0.8f) {
            float adjustment = ModConfig.seasons.getSeasonProperties(subSeason).biomeTempAdjustment();
            temp = Mth.m_14036_(temp + adjustment, -0.5f, 2.0f);
        }
        return temp;
    }
}
