package dev.sunlit.bettersunlit.client;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import traben.entity_model_features.EMF;
import traben.entity_model_features.EMFManager;
import traben.entity_model_features.utils.EMFResourceCaching;
import traben.entity_texture_features.features.ETFManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Parallelizes EMF {@code ModelLayerLocation} model baking and CEM/JEM tree construction across
 * worker threads during {@code EntityModelSet.onResourceManagerReload}, preserving EMF/Fusion
 * post-bake hooks and {@code EMFManager.lastCreatedRootModelPart} handoff on the render thread.
 */
public final class EmfModelPrebakePerf {
    private static final ConcurrentHashMap<ModelLayerLocation, ModelPart> PREBAKED = new ConcurrentHashMap<>();
    private static final ThreadLocal<ModelLayerLocation> CURRENT_LAYER = new ThreadLocal<>();
    private static final ThreadLocal<ModelPart> PENDING_EMF_RESULT = new ThreadLocal<>();

    private EmfModelPrebakePerf() {
    }

    /**
     * Thread-safe {@link HashMap} subclass preserving exact bytecode field type compatibility
     * for {@code ETFManager} caches that declare concrete {@code HashMap} fields.
     */
    public static final class SyncHashMap<K, V> extends HashMap<K, V> {
        @Override public synchronized int size() { return super.size(); }
        @Override public synchronized boolean isEmpty() { return super.isEmpty(); }
        @Override public synchronized V get(Object key) { return super.get(key); }
        @Override public synchronized V getOrDefault(Object key, V defaultValue) { return super.getOrDefault(key, defaultValue); }
        @Override public synchronized boolean containsKey(Object key) { return super.containsKey(key); }
        @Override public synchronized boolean containsValue(Object value) { return super.containsValue(value); }
        @Override public synchronized V put(K key, V value) { return super.put(key, value); }
        @Override public synchronized V putIfAbsent(K key, V value) { return super.putIfAbsent(key, value); }
        @Override public synchronized void putAll(Map<? extends K, ? extends V> m) { super.putAll(m); }
        @Override public synchronized V remove(Object key) { return super.remove(key); }
        @Override public synchronized boolean remove(Object key, Object value) { return super.remove(key, value); }
        @Override public synchronized boolean replace(K key, V oldValue, V newValue) { return super.replace(key, oldValue, newValue); }
        @Override public synchronized V replace(K key, V value) { return super.replace(key, value); }
        @Override public synchronized void replaceAll(BiFunction<? super K, ? super V, ? extends V> fn) { super.replaceAll(fn); }
        @Override public synchronized void clear() { super.clear(); }
        @Override public synchronized V computeIfAbsent(K key, Function<? super K, ? extends V> fn) { return super.computeIfAbsent(key, fn); }
        @Override public synchronized V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> fn) { return super.computeIfPresent(key, fn); }
        @Override public synchronized V compute(K key, BiFunction<? super K, ? super V, ? extends V> fn) { return super.compute(key, fn); }
        @Override public synchronized V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> fn) { return super.merge(key, value, fn); }
        @Override public synchronized void forEach(BiConsumer<? super K, ? super V> action) { super.forEach(action); }
        @Override public synchronized boolean equals(Object o) { return super.equals(o); }
        @Override public synchronized int hashCode() { return super.hashCode(); }
        @Override public synchronized String toString() { return super.toString(); }
        @Override public synchronized Object clone() { return super.clone(); }
    }

    public static boolean hasSpecialContext() {
        EMFManager mgr = EMFManager.getInstance();
        if (mgr == null) {
            return false;
        }
        if (mgr.currentBlockEntityTypeLoading != null) {
            return true;
        }
        String specified = mgr.currentSpecifiedModelLoading.get();
        return specified != null && !specified.isEmpty();
    }

    private static boolean isSpecialLayer(ModelLayerLocation loc) {
        String path = loc.m_171123_().m_135815_();
        return path.contains("boat")
                || path.contains("chest")
                || path.contains("sign")
                || path.contains("banner")
                || path.equals("bed")
                || path.contains("bed_")
                || path.contains("shulker")
                || path.contains("skull")
                || path.contains("shield")
                || path.contains("trident")
                || path.contains("bell")
                || path.contains("conduit")
                || path.contains("decorated_pot")
                || path.contains("book")
                || path.contains("piston");
    }

    public static void schedulePrebake(ResourceManager rm, Map<ModelLayerLocation, LayerDefinition> roots) {
        PREBAKED.clear();
        CURRENT_LAYER.remove();
        PENDING_EMF_RESULT.remove();
        if (rm == null || roots == null || roots.size() < 16 || EMF.testForForgeLoadingError()) {
            return;
        }
        EMFManager emf = EMFManager.getInstance();
        ETFManager.getInstance();
        // Warm EMFResourceCaching.populateCacheIfNeeded synchronously before worker threads run.
        EMFResourceCaching.resourceExists(rm, new ResourceLocation("minecraft", "cem/__sunlit_warm__.jem"));

        List<Map.Entry<ModelLayerLocation, LayerDefinition>> work = new ArrayList<>(roots.size());
        for (Map.Entry<ModelLayerLocation, LayerDefinition> entry : roots.entrySet()) {
            if (!isSpecialLayer(entry.getKey())) {
                work.add(entry);
            }
        }
        if (work.size() < 16) {
            return;
        }

        final ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        IntStream.range(0, work.size()).parallel().forEach(i -> {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            if (previous != tccl) {
                thread.setContextClassLoader(tccl);
            }
            try {
                Map.Entry<ModelLayerLocation, LayerDefinition> entry = work.get(i);
                ModelPart raw = entry.getValue().m_171564_();
                ModelPart baked = emf.injectIntoModelRootGetter(entry.getKey(), raw);
                PREBAKED.put(entry.getKey(), baked != null ? baked : raw);
            } catch (Throwable ignored) {
                // Fall back to synchronous Render-thread evaluation for any entry that fails off-thread.
            } finally {
                if (thread.getContextClassLoader() != previous) {
                    thread.setContextClassLoader(previous);
                }
            }
        });
        EMFManager.lastCreatedRootModelPart = null;
    }

    public static void beginLookup(ModelLayerLocation layer) {
        CURRENT_LAYER.set(layer);
        PENDING_EMF_RESULT.remove();
    }

    public static ModelPart takeRawAndStageEmfResult(LayerDefinition def) {
        ModelLayerLocation loc = CURRENT_LAYER.get();
        if (loc != null && !hasSpecialContext()) {
            ModelPart pre = PREBAKED.remove(loc);
            if (pre != null) {
                PENDING_EMF_RESULT.set(pre);
                return pre;
            }
        }
        return def.m_171564_();
    }

    public static ModelPart consumePendingEmfResult() {
        ModelPart pre = PENDING_EMF_RESULT.get();
        if (pre != null) {
            PENDING_EMF_RESULT.remove();
        }
        return pre;
    }

    public static void endLookup() {
        CURRENT_LAYER.remove();
        PENDING_EMF_RESULT.remove();
    }
}
