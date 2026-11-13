package dev.sunlit.bettersunlit.client;

import com.google.common.collect.Multimap;
import dev.sunlit.bettersunlit.perf.SeasonPerf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import sereneseasons.api.season.ISeasonColorProvider;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.init.ModConfig;
import sereneseasons.init.ModTags;
import sereneseasons.season.SeasonColorHandlers;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe zero-allocation biome holder, RegistryAccess snapshot, HSV saturation,
 * and ColorOverride lookup helper for SereneSeasons' chunk-meshing color resolvers.
 */
public final class SeasonColorPerf {
    private static final Object REGISTRY_LOCK = new Object();
    private static final Object OVERRIDE_LOCK = new Object();

    private static volatile RegistrySnapshot registrySnapshot;
    private static final AtomicLong OVERRIDE_EPOCH = new AtomicLong(0L);
    private static volatile OverrideSnapshot overrideSnapshot;

    private SeasonColorPerf() {}

    private record RegistrySnapshot(RegistryAccess access, Registry<Biome> registry,
                                    IdentityHashMap<Biome, Holder<Biome>> map) {}

    private record OverrideSnapshot(long epoch,
                                    SeasonColorHandlers.ColorOverride[] grass,
                                    SeasonColorHandlers.ColorOverride[] foliage) {}

    public static void onOverrideRegistered() {
        OVERRIDE_EPOCH.incrementAndGet();
    }

    public static int resolveColors(SeasonColorHandlers.ResolverType type, Biome biome, double x, double z,
                                    ColorResolver originalGrass, ColorResolver originalFoliage,
                                    Multimap<SeasonColorHandlers.ResolverType, SeasonColorHandlers.ColorOverride> overrides) {
        int originalColor = switch (type) {
            case GRASS -> originalGrass.m_130045_(biome, x, z);
            case FOLIAGE -> originalFoliage.m_130045_(biome, x, z);
        };

        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (level == null) return originalColor;

        RegistrySnapshot snap = getRegistrySnapshot(level.m_9598_());
        Holder<Biome> holder = resolveHolder(snap, biome);
        if (holder == null) return originalColor;

        ISeasonState seasonState = SeasonHelper.getSeasonState(level);
        ISeasonColorProvider provider = holder.m_203656_(ModTags.Biomes.TROPICAL_BIOMES)
                ? seasonState.getTropicalSeason()
                : seasonState.getSubSeason();

        ResourceKey<Level> dimension = level.m_46472_();
        int newColor = switch (type) {
            case GRASS -> applySeasonalGrassColouring(dimension, provider, holder, originalColor);
            case FOLIAGE -> applySeasonalFoliageColouring(dimension, provider, holder, originalColor);
        };

        if (overrides.isEmpty()) {
            return newColor;
        }
        SeasonColorHandlers.ColorOverride[] list = getOverridesSnapshot(type, overrides);
        int overrideColor = newColor;
        for (int i = 0; i < list.length; i++) {
            overrideColor = list[i].apply(originalColor, newColor, overrideColor, holder, x, z);
        }
        return overrideColor;
    }

    public static int applySeasonalGrassColouring(ISeasonColorProvider provider, Holder<Biome> biome, int originalColour) {
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (level == null) return originalColour;
        return applySeasonalGrassColouring(level.m_46472_(), provider, biome, originalColour);
    }

    public static int applySeasonalGrassColouring(ResourceKey<Level> dimension, ISeasonColorProvider provider,
                                                  Holder<Biome> biome, int originalColour) {
        if (biome.m_203656_(ModTags.Biomes.BLACKLISTED_BIOMES)
                || !SeasonPerf.isDimensionWhitelisted(ModConfig.seasons.whitelistedDimensions, dimension)) {
            return originalColour;
        }
        int overlay = provider.getGrassOverlay();
        float saturationMultiplier = provider.getGrassSaturationMultiplier();
        if (!ModConfig.seasons.changeGrassColor) {
            overlay = Season.SubSeason.MID_SUMMER.getGrassOverlay();
            saturationMultiplier = Season.SubSeason.MID_SUMMER.getGrassSaturationMultiplier();
        }
        int blended = overlay == 0xFFFFFF ? originalColour : overlayBlend(originalColour, overlay);
        int mixed = biome.m_203656_(ModTags.Biomes.LESSER_COLOR_CHANGE_BIOMES)
                ? mixColours(blended, originalColour, 0.75f)
                : blended;
        return saturationMultiplier != -1.0f ? saturateColour(mixed, saturationMultiplier) : mixed;
    }

    public static int applySeasonalFoliageColouring(ISeasonColorProvider provider, Holder<Biome> biome, int originalColour) {
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (level == null) return originalColour;
        return applySeasonalFoliageColouring(level.m_46472_(), provider, biome, originalColour);
    }

    public static int applySeasonalFoliageColouring(ResourceKey<Level> dimension, ISeasonColorProvider provider,
                                                    Holder<Biome> biome, int originalColour) {
        if (biome.m_203656_(ModTags.Biomes.BLACKLISTED_BIOMES)
                || !SeasonPerf.isDimensionWhitelisted(ModConfig.seasons.whitelistedDimensions, dimension)) {
            return originalColour;
        }
        int overlay = provider.getFoliageOverlay();
        float saturationMultiplier = provider.getFoliageSaturationMultiplier();
        if (!ModConfig.seasons.changeFoliageColor) {
            overlay = Season.SubSeason.MID_SUMMER.getFoliageOverlay();
            saturationMultiplier = Season.SubSeason.MID_SUMMER.getFoliageSaturationMultiplier();
        }
        int blended = overlay == 0xFFFFFF ? originalColour : overlayBlend(originalColour, overlay);
        int mixed = biome.m_203656_(ModTags.Biomes.LESSER_COLOR_CHANGE_BIOMES)
                ? mixColours(blended, originalColour, 0.75f)
                : blended;
        return saturationMultiplier != -1.0f ? saturateColour(mixed, saturationMultiplier) : mixed;
    }

    public static int overlayBlend(int colour1, int colour2) {
        int r = overlayBlendChannel((colour1 >> 16) & 255, (colour2 >> 16) & 255);
        int g = overlayBlendChannel((colour1 >> 8) & 255, (colour2 >> 8) & 255);
        int b = overlayBlendChannel(colour1 & 255, colour2 & 255);
        return ((r & 255) << 16) | ((g & 255) << 8) | (b & 255);
    }

    public static int overlayBlendChannel(int a, int b) {
        if (a < 128) {
            return multiplyColours(2 * a, b);
        }
        return 255 - multiplyColours(2 * (255 - a), 255 - b);
    }

    public static int multiplyColours(int colour1, int colour2) {
        return (int) (((float) colour1 / 255.0f) * ((float) colour2 / 255.0f) * 255.0f);
    }

    public static int mixColours(int colour1, int colour2, float alpha) {
        if (alpha > 1.0f) {
            alpha = 1.0f;
        } else if (alpha < 0.0f) {
            alpha = 0.0f;
        }
        float invAlpha = 1.0f - alpha;
        int a1 = (colour1 >> 24) & 255;
        int r1 = (colour1 & 0xFF0000) >> 16;
        int g1 = (colour1 & 0x00FF00) >> 8;
        int b1 = colour1 & 255;
        int a2 = (colour2 >> 24) & 255;
        int r2 = (colour2 & 0xFF0000) >> 16;
        int g2 = (colour2 & 0x00FF00) >> 8;
        int b2 = colour2 & 255;
        int a = (int) (a1 * invAlpha + a2 * alpha);
        int r = (int) (r1 * invAlpha + r2 * alpha);
        int g = (int) (g1 * invAlpha + g2 * alpha);
        int b = (int) (b1 * invAlpha + b2 * alpha);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Allocation-free equivalent of {@link sereneseasons.util.SeasonColorUtil#saturateColour(int, float)}. */
    public static int saturateColour(int colour, float saturationMultiplier) {
        double rd = (double) ((colour >> 16) & 255) / 255.0d;
        double gd = (double) ((colour >> 8) & 255) / 255.0d;
        double bd = (double) (colour & 255) / 255.0d;

        double min = rd < gd ? rd : gd;
        if (bd < min) min = bd;
        double max = rd > gd ? rd : gd;
        if (bd > max) max = bd;

        double v = max;
        double delta = max - min;
        double h;
        double s;
        if (delta < 1.0E-5d) {
            s = 0.0d;
            h = 0.0d;
        } else {
            if (max > 0.0d) {
                s = delta / max;
            } else {
                s = 0.0d;
                h = -1.0d;
                return packScaledRgb(v, v, v);
            }
            if (rd >= max) {
                h = (gd - bd) / delta;
            } else if (gd >= max) {
                h = 2.0d + (bd - rd) / delta;
            } else {
                h = 4.0d + (rd - gd) / delta;
            }
            h *= 60.0d;
            if (h < 0.0d) {
                h += 360.0d;
            }
        }

        s *= (double) saturationMultiplier;
        if (s <= 0.0d) {
            return packScaledRgb(v, v, v);
        }
        double hh = h >= 360.0d ? 0.0d : h;
        hh /= 60.0d;
        int sector = (int) hh;
        double ff = hh - (double) sector;
        double p = v * (1.0d - s);
        double q = v * (1.0d - s * ff);
        double t = v * (1.0d - s * (1.0d - ff));
        return switch (sector) {
            case 0 -> packScaledRgb(v, t, p);
            case 1 -> packScaledRgb(q, v, p);
            case 2 -> packScaledRgb(p, v, t);
            case 3 -> packScaledRgb(p, q, v);
            case 4 -> packScaledRgb(t, p, v);
            default -> packScaledRgb(v, p, q);
        };
    }

    private static int packScaledRgb(double r, double g, double b) {
        int ir = (int) (r * 255.0d);
        int ig = (int) (g * 255.0d);
        int ib = (int) (b * 255.0d);
        return ((ir & 255) << 16) | ((ig & 255) << 8) | (ib & 255);
    }

    private static RegistrySnapshot getRegistrySnapshot(RegistryAccess access) {
        RegistrySnapshot snap = registrySnapshot;
        if (snap == null || snap.access != access) {
            snap = buildRegistrySnapshotFromAccess(access);
        }
        return snap;
    }

    public static Holder<Biome> resolveHolder(Registry<Biome> registry, Biome biome) {
        if (biome == null) return null;
        RegistrySnapshot snap = registrySnapshot;
        if (snap == null || snap.registry != registry) {
            snap = buildRegistrySnapshot(null, registry);
        }
        return resolveHolder(snap, biome);
    }

    private static Holder<Biome> resolveHolder(RegistrySnapshot snap, Biome biome) {
        if (biome == null) return null;
        Holder<Biome> holder = snap.map.get(biome);
        if (holder != null) return holder;
        // Fallback only for synthetic/unregistered Biome instances outside the frozen registry.
        return snap.registry.m_7854_(biome).flatMap(snap.registry::m_203636_).orElse(null);
    }

    private static RegistrySnapshot buildRegistrySnapshotFromAccess(RegistryAccess access) {
        Registry<Biome> registry = access.m_175515_(Registries.f_256952_);
        return buildRegistrySnapshot(access, registry);
    }

    private static RegistrySnapshot buildRegistrySnapshot(RegistryAccess access, Registry<Biome> registry) {
        synchronized (REGISTRY_LOCK) {
            RegistrySnapshot current = registrySnapshot;
            if (current != null && ((access != null && current.access == access)
                    || (access == null && current.registry == registry))) {
                return current;
            }
            IdentityHashMap<Biome, Holder<Biome>> built = new IdentityHashMap<>(256);
            registry.m_203611_().forEach(ref -> {
                Biome value = ref.m_203334_();
                if (value != null) built.put(value, ref);
            });
            RegistrySnapshot next = new RegistrySnapshot(access, registry, built);
            registrySnapshot = next;
            return next;
        }
    }

    /** Extracted pure helper for offline verification of Multimap snapshot ordering & invalidation. */
    public static SeasonColorHandlers.ColorOverride[] getOverridesSnapshot(
            SeasonColorHandlers.ResolverType type,
            Multimap<SeasonColorHandlers.ResolverType, SeasonColorHandlers.ColorOverride> overrides) {
        long epoch = OVERRIDE_EPOCH.get();
        int total = overrides.size();
        OverrideSnapshot snap = overrideSnapshot;
        if (snap == null || snap.epoch != epoch
                || (snap.grass.length + snap.foliage.length != total)) {
            synchronized (OVERRIDE_LOCK) {
                epoch = OVERRIDE_EPOCH.get();
                total = overrides.size();
                snap = overrideSnapshot;
                if (snap == null || snap.epoch != epoch
                        || (snap.grass.length + snap.foliage.length != total)) {
                    SeasonColorHandlers.ColorOverride[] grass = toArray(overrides.get(SeasonColorHandlers.ResolverType.GRASS));
                    SeasonColorHandlers.ColorOverride[] foliage = toArray(overrides.get(SeasonColorHandlers.ResolverType.FOLIAGE));
                    snap = new OverrideSnapshot(epoch, grass, foliage);
                    overrideSnapshot = snap;
                }
            }
        }
        return type == SeasonColorHandlers.ResolverType.GRASS ? snap.grass : snap.foliage;
    }

    private static SeasonColorHandlers.ColorOverride[] toArray(Collection<SeasonColorHandlers.ColorOverride> col) {
        return col.isEmpty()
                ? new SeasonColorHandlers.ColorOverride[0]
                : col.toArray(new SeasonColorHandlers.ColorOverride[0]);
    }

    public static void clearCachesForTests() {
        registrySnapshot = null;
        overrideSnapshot = null;
        OVERRIDE_EPOCH.incrementAndGet();
    }
}
