package dev.sunlit.bettersunlit.client;

import mezz.jei.common.platform.IPlatformRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegistryManager;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Identity-validated cache for JEI's {@code RegistryWrapper.getRegistry(ResourceKey)},
 * automatically refreshing whenever Forge swaps the backing {@link ForgeRegistry} instance.
 */
public final class JeiRegistryPerf {

    private static final ConcurrentHashMap<ResourceKey<?>, Entry> CACHE = new ConcurrentHashMap<>();

    private JeiRegistryPerf() {
    }

    @SuppressWarnings("unchecked")
    public static <T> IPlatformRegistry<T> getRegistry(ResourceKey<? extends Registry<T>> key) {
        ForgeRegistry<T> active = RegistryManager.ACTIVE.getRegistry(key.m_135782_());
        Entry entry = CACHE.get(key);
        if (entry != null && entry.registry == active) {
            return (IPlatformRegistry<T>) entry.wrapper;
        }
        IPlatformRegistry<T> created = new CachedForgeRegistry<>(active);
        CACHE.put(key, new Entry(active, created));
        return created;
    }

    @SuppressWarnings("unchecked")
    public static <T, R> IPlatformRegistry<T> getOrCreate(
            ResourceKey<?> key,
            R activeRegistry,
            Function<R, IPlatformRegistry<T>> factory) {
        Entry entry = CACHE.get(key);
        if (entry != null && entry.registry == activeRegistry) {
            return (IPlatformRegistry<T>) entry.wrapper;
        }
        IPlatformRegistry<T> created = factory.apply(activeRegistry);
        CACHE.put(key, new Entry(activeRegistry, created));
        return created;
    }

    public static void invalidate() {
        CACHE.clear();
    }

    public static int size() {
        return CACHE.size();
    }

    private static final class Entry {
        final Object registry;
        final IPlatformRegistry<?> wrapper;

        Entry(Object registry, IPlatformRegistry<?> wrapper) {
            this.registry = registry;
            this.wrapper = wrapper;
        }
    }

    private static final class CachedForgeRegistry<T> implements IPlatformRegistry<T> {
        private final ForgeRegistry<T> forgeRegistry;

        CachedForgeRegistry(ForgeRegistry<T> forgeRegistry) {
            this.forgeRegistry = forgeRegistry;
        }

        @Override
        public Stream<T> getValues() {
            return StreamSupport.stream(forgeRegistry.spliterator(), false);
        }

        @Override
        public Optional<T> getValue(ResourceLocation resourceLocation) {
            return Optional.ofNullable(forgeRegistry.getValue(resourceLocation));
        }

        @Override
        public int getId(T entry) {
            return forgeRegistry.getID(entry);
        }

        @Override
        public Optional<T> getValue(int id) {
            return Optional.ofNullable(forgeRegistry.getValue(id));
        }

        @Override
        public boolean contains(T entry) {
            return forgeRegistry.containsValue(entry);
        }

        @Override
        public Optional<ResourceLocation> getRegistryName(T entry) {
            return forgeRegistry.getResourceKey(entry).map(ResourceKey::m_135782_);
        }
    }
}
