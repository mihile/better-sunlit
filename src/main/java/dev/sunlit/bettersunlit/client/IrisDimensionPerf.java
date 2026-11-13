package dev.sunlit.bettersunlit.client;

import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Canonicalizes Emberlens/Iris dimension IDs so same-dimension checks in
 * {@code MixinMinecraft_PipelineManagement} compare stable object references
 * and avoid per-frame {@link NamespacedId} allocations and spurious shader rebuilds.
 */
public final class IrisDimensionPerf {
    private static final int MAX_CUSTOM_DIMENSIONS = 256;
    private static final ConcurrentHashMap<ResourceKey<Level>, NamespacedId> CUSTOM_CACHE = new ConcurrentHashMap<>();
    private static volatile ResourceKey<Level> overworldKey;
    private static volatile ResourceKey<Level> netherKey;
    private static volatile ResourceKey<Level> endKey;

    private IrisDimensionPerf() {}

    public static NamespacedId getCurrentDimension(NamespacedId lastDimension) {
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (level == null) {
            return lastDimension;
        }
        return canonicalDimensionId(level.m_46472_());
    }

    public static NamespacedId canonicalDimensionId(ResourceKey<Level> key) {
        if (key == null) return DimensionId.OVERWORLD;
        if (key == overworldKey) return DimensionId.OVERWORLD;
        if (key == netherKey) return DimensionId.NETHER;
        if (key == endKey) return DimensionId.END;

        NamespacedId cached = CUSTOM_CACHE.get(key);
        if (cached != null) return cached;

        ResourceLocation loc = key.m_135782_();
        String ns = loc.m_135827_();
        String path = loc.m_135815_();
        NamespacedId resolved;
        if ("minecraft".equals(ns)) {
            if ("overworld".equals(path)) {
                resolved = DimensionId.OVERWORLD;
                if (overworldKey == null) overworldKey = key;
            } else if ("the_nether".equals(path)) {
                resolved = DimensionId.NETHER;
                if (netherKey == null) netherKey = key;
            } else if ("the_end".equals(path)) {
                resolved = DimensionId.END;
                if (endKey == null) endKey = key;
            } else {
                resolved = new NamespacedId(ns, path);
            }
        } else {
            resolved = new NamespacedId(ns, path);
        }

        if (CUSTOM_CACHE.size() < MAX_CUSTOM_DIMENSIONS) {
            NamespacedId prev = CUSTOM_CACHE.putIfAbsent(key, resolved);
            if (prev != null) resolved = prev;
        }
        return resolved;
    }

    public static void clearCachesForTests() {
        overworldKey = null;
        netherKey = null;
        endKey = null;
        CUSTOM_CACHE.clear();
    }
}
