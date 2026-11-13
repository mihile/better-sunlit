package dev.sunlit.bettersunlit.client;

import com.google.common.collect.Multimap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import sereneseasons.api.season.ISeasonColorProvider;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.init.ModTags;
import sereneseasons.season.SeasonColorHandlers;
import sereneseasons.util.SeasonColorUtil;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe zero-allocation biome holder & ColorOverride lookup helper for SereneSeasons'
 * chunk-meshing color resolvers (called heavily on RenderThread and Sodium/Embeddium workers).
 */
public final class SeasonColorPerf {
    private static final Object REGISTRY_LOCK = new Object();
    private static final Object OVERRIDE_LOCK = new Object();

    private static volatile RegistrySnapshot registrySnapshot;
    private static final AtomicLong OVERRIDE_EPOCH = new AtomicLong(0L);
    private static volatile OverrideSnapshot overrideSnapshot;

    private SeasonColorPerf() {}

    private record RegistrySnapshot(Registry<Biome> registry, IdentityHashMap<Biome, Holder<Biome>> map) {}

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

        Registry<Biome> registry = level.m_9598_().m_175515_(Registries.f_256952_);
        Holder<Biome> holder = resolveHolder(registry, biome);
        if (holder == null) return originalColor;

        ISeasonState seasonState = SeasonHelper.getSeasonState(level);
        ISeasonColorProvider provider = holder.m_203656_(ModTags.Biomes.TROPICAL_BIOMES)
                ? seasonState.getTropicalSeason()
                : seasonState.getSubSeason();

        int newColor = switch (type) {
            case GRASS -> SeasonColorUtil.applySeasonalGrassColouring(provider, holder, originalColor);
            case FOLIAGE -> SeasonColorUtil.applySeasonalFoliageColouring(provider, holder, originalColor);
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

    public static Holder<Biome> resolveHolder(Registry<Biome> registry, Biome biome) {
        if (biome == null) return null;
        RegistrySnapshot snap = registrySnapshot;
        if (snap == null || snap.registry != registry) {
            snap = buildRegistrySnapshot(registry);
        }
        Holder<Biome> holder = snap.map.get(biome);
        if (holder != null) return holder;
        // Fallback only for synthetic/unregistered Biome instances outside the frozen registry.
        return registry.m_7854_(biome).flatMap(registry::m_203636_).orElse(null);
    }

    private static RegistrySnapshot buildRegistrySnapshot(Registry<Biome> registry) {
        synchronized (REGISTRY_LOCK) {
            RegistrySnapshot current = registrySnapshot;
            if (current != null && current.registry == registry) {
                return current;
            }
            IdentityHashMap<Biome, Holder<Biome>> built = new IdentityHashMap<>(256);
            registry.m_203611_().forEach(ref -> {
                Biome value = ref.m_203334_();
                if (value != null) built.put(value, ref);
            });
            RegistrySnapshot next = new RegistrySnapshot(registry, built);
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
