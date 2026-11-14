package dev.sunlit.bettersunlit.client;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.Tickable;
import net.minecraft.resources.ResourceLocation;

import java.util.AbstractSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe and re-entrancy-safe collection factories for {@code TextureManager} so off-thread
 * texture registrations/releases (such as packet decoders or async loaders) cannot throw
 * {@link java.util.ConcurrentModificationException} during {@code TextureManager.m_7673_()}.
 */
public final class TextureManagerPerf {
    private TextureManagerPerf() {}

    public static Map<ResourceLocation, AbstractTexture> concurrentByPath(
            Map<ResourceLocation, AbstractTexture> existing) {
        Map<ResourceLocation, AbstractTexture> copy =
                existing != null && !existing.isEmpty() ? new HashMap<>(existing) : new HashMap<>();
        return Collections.synchronizedMap(copy);
    }

    public static Map<String, Integer> concurrentPrefixRegister(Map<String, Integer> existing) {
        Map<String, Integer> copy =
                existing != null && !existing.isEmpty() ? new HashMap<>(existing) : new HashMap<>();
        return Collections.synchronizedMap(copy);
    }

    public static Set<Tickable> concurrentTickables(Set<Tickable> existing) {
        NullSafeConcurrentTickableSet set = new NullSafeConcurrentTickableSet();
        if (existing != null) {
            for (Tickable tickable : existing) {
                if (tickable != null) {
                    set.add(tickable);
                }
            }
        }
        return set;
    }

    private static final class NullSafeConcurrentTickableSet extends AbstractSet<Tickable> {
        private final Set<Tickable> delegate = ConcurrentHashMap.newKeySet();

        @Override
        public boolean add(Tickable tickable) {
            return tickable != null && this.delegate.add(tickable);
        }

        @Override
        public boolean contains(Object o) {
            return o != null && this.delegate.contains(o);
        }

        @Override
        public boolean remove(Object o) {
            return o != null && this.delegate.remove(o);
        }

        @Override
        public void clear() {
            this.delegate.clear();
        }

        @Override
        public int size() {
            return this.delegate.size();
        }

        @Override
        public Iterator<Tickable> iterator() {
            return this.delegate.iterator();
        }
    }
}
