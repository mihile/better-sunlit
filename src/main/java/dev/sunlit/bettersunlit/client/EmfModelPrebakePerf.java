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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Parallelizes {@code ModelLayerLocation} baking, plus CEM/JEM construction when EMF has reviewed
 * thread-local context and concurrent caches, preserving EMF/Fusion
 * post-bake hooks and {@code EMFManager.lastCreatedRootModelPart} handoff on the render thread.
 */
public final class EmfModelPrebakePerf {
    private static final ConcurrentHashMap<ModelLayerLocation, PrebakeTask> PREBAKED = new ConcurrentHashMap<>();
    private static final ThreadLocal<ModelLayerLocation> CURRENT_LAYER = new ThreadLocal<>();
    private static final ThreadLocal<ModelPart> PENDING_EMF_RESULT = new ThreadLocal<>();
    private static final EmfLoadingContext CONTEXT = EmfLoadingContext.resolve(EMFManager.class);
    private static volatile boolean prebakedEmfResults;
    private static volatile List<ForkJoinTask<?>> activeWorkers;

    private static final class PrebakeTask {
        final ModelLayerLocation loc;
        final LayerDefinition def;
        final AtomicBoolean claimed = new AtomicBoolean(false);
        final CompletableFuture<ModelPart> future = new CompletableFuture<>();

        PrebakeTask(ModelLayerLocation loc, LayerDefinition def) {
            this.loc = loc;
            this.def = def;
        }
    }

    private EmfModelPrebakePerf() {
    }

    /**
     * Lock-free, null-safe {@link HashMap} subclass backed by a {@link ConcurrentHashMap},
     * preserving exact bytecode field type compatibility for {@code ETFManager} caches that
     * declare concrete {@code HashMap} fields without acquiring a global monitor on every render lookup.
     */
    public static final class SyncHashMap<K, V> extends HashMap<K, V> {
        private static final Object NULL_KEY = new Object();
        private static final Object NULL_VAL = new Object();
        private final ConcurrentHashMap<Object, Object> delegate = new ConcurrentHashMap<>();

        public SyncHashMap() {
            super(0);
        }

        private static Object wrapKey(Object k) {
            return k == null ? NULL_KEY : k;
        }

        @SuppressWarnings("unchecked")
        private static <K> K unwrapKey(Object k) {
            return k == NULL_KEY ? null : (K) k;
        }

        private static Object wrapVal(Object v) {
            return v == null ? NULL_VAL : v;
        }

        @SuppressWarnings("unchecked")
        private static <V> V unwrapVal(Object v) {
            return v == null || v == NULL_VAL ? null : (V) v;
        }

        @Override public int size() { return delegate.size(); }
        @Override public boolean isEmpty() { return delegate.isEmpty(); }
        @Override public V get(Object key) { return unwrapVal(delegate.get(wrapKey(key))); }

        @Override
        public V getOrDefault(Object key, V defaultValue) {
            Object raw = delegate.get(wrapKey(key));
            return raw == null ? defaultValue : unwrapVal(raw);
        }

        @Override public boolean containsKey(Object key) { return delegate.containsKey(wrapKey(key)); }
        @Override public boolean containsValue(Object value) { return delegate.containsValue(wrapVal(value)); }

        @Override
        public V put(K key, V value) {
            return unwrapVal(delegate.put(wrapKey(key), wrapVal(value)));
        }

        @Override
        public V putIfAbsent(K key, V value) {
            return unwrapVal(delegate.putIfAbsent(wrapKey(key), wrapVal(value)));
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> m) {
            if (m != null && !m.isEmpty()) {
                for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
                    delegate.put(wrapKey(e.getKey()), wrapVal(e.getValue()));
                }
            }
        }

        @Override public V remove(Object key) { return unwrapVal(delegate.remove(wrapKey(key))); }
        @Override public boolean remove(Object key, Object value) { return delegate.remove(wrapKey(key), wrapVal(value)); }
        @Override public boolean replace(K key, V oldValue, V newValue) { return delegate.replace(wrapKey(key), wrapVal(oldValue), wrapVal(newValue)); }
        @Override public V replace(K key, V value) { return unwrapVal(delegate.replace(wrapKey(key), wrapVal(value))); }

        @Override
        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> fn) {
            delegate.replaceAll((k, v) -> wrapVal(fn.apply(unwrapKey(k), unwrapVal(v))));
        }

        @Override public void clear() { delegate.clear(); }

        @Override
        public V computeIfAbsent(K key, Function<? super K, ? extends V> fn) {
            Object wk = wrapKey(key);
            Object existing = delegate.get(wk);
            if (existing != null) {
                return unwrapVal(existing);
            }
            Object computed = delegate.computeIfAbsent(wk, k -> {
                V v = fn.apply(key);
                return v == null ? null : wrapVal(v);
            });
            return unwrapVal(computed);
        }

        @Override
        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> fn) {
            return unwrapVal(delegate.computeIfPresent(wrapKey(key), (k, v) -> {
                V next = fn.apply(key, unwrapVal(v));
                return next == null ? null : wrapVal(next);
            }));
        }

        @Override
        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> fn) {
            return unwrapVal(delegate.compute(wrapKey(key), (k, v) -> {
                V next = fn.apply(key, unwrapVal(v));
                return next == null ? null : wrapVal(next);
            }));
        }

        @Override
        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> fn) {
            return unwrapVal(delegate.merge(wrapKey(key), wrapVal(value), (oldV, newV) -> {
                V merged = fn.apply(unwrapVal(oldV), unwrapVal(newV));
                return merged == null ? null : wrapVal(merged);
            }));
        }

        @Override
        public void forEach(BiConsumer<? super K, ? super V> action) {
            delegate.forEach((k, v) -> action.accept(unwrapKey(k), unwrapVal(v)));
        }

        @Override
        public java.util.Set<K> keySet() {
            java.util.LinkedHashSet<K> keys = new java.util.LinkedHashSet<>(delegate.size() * 2 + 1);
            for (Object k : delegate.keySet()) {
                keys.add(unwrapKey(k));
            }
            return keys;
        }

        @Override
        public java.util.Collection<V> values() {
            java.util.ArrayList<V> vals = new java.util.ArrayList<>(delegate.size());
            for (Object v : delegate.values()) {
                vals.add(unwrapVal(v));
            }
            return vals;
        }

        @Override
        public java.util.Set<Map.Entry<K, V>> entrySet() {
            java.util.LinkedHashSet<Map.Entry<K, V>> entries = new java.util.LinkedHashSet<>(delegate.size() * 2 + 1);
            for (Map.Entry<Object, Object> e : delegate.entrySet()) {
                entries.add(new java.util.AbstractMap.SimpleEntry<>(unwrapKey(e.getKey()), unwrapVal(e.getValue())));
            }
            return entries;
        }

        @Override public boolean equals(Object o) { return o == this || (o instanceof Map<?, ?> m && entrySet().equals(m.entrySet())); }
        @Override public int hashCode() { return entrySet().hashCode(); }
        @Override public String toString() { return delegate.toString(); }
        @Override
        public Object clone() {
            SyncHashMap<K, V> copy = new SyncHashMap<>();
            copy.delegate.putAll(this.delegate);
            return copy;
        }
    }

    public static boolean hasSpecialContext() {
        return CONTEXT.hasSpecialContext(EMFManager.getInstance());
    }

    static boolean isSpecialLayer(ModelLayerLocation loc) {
        // Keep these layers in the renderer's normal EMF bake context. Prebaking
        // them also skips renderer-time animation registration when a result is reused.
        if ("rottencreatures".equals(loc.m_171123_().m_135827_())) {
            return true;
        }
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

    private static void awaitActiveWorkers() {
        List<ForkJoinTask<?>> workers = activeWorkers;
        if (workers == null) {
            return;
        }
        activeWorkers = null;
        for (int i = 0, n = workers.size(); i < n; i++) {
            try {
                workers.get(i).join();
            } catch (Throwable ignored) {
            }
        }
        if (prebakedEmfResults) {
            EMFManager.lastCreatedRootModelPart = null;
        }
    }

    private static ModelPart runPrebakeTask(
            PrebakeTask task,
            EMFManager emf,
            boolean parallelEmf,
            boolean scopedValidation) {
        ModelPart raw = task.def.m_171564_();
        ModelPart baked = raw;
        if (scopedValidation) {
            baked = EmfValidationScope.bake(emf, () -> emf.injectIntoModelRootGetter(task.loc, raw));
        } else if (parallelEmf) {
            baked = emf.injectIntoModelRootGetter(task.loc, raw);
        }
        return baked != null ? baked : raw;
    }

    public static void schedulePrebake(ResourceManager rm, Map<ModelLayerLocation, LayerDefinition> roots) {
        awaitActiveWorkers();
        PREBAKED.clear();
        CURRENT_LAYER.remove();
        PENDING_EMF_RESULT.remove();
        prebakedEmfResults = false;
        if (rm == null || roots == null || roots.size() < 16 || EMF.testForForgeLoadingError()) {
            return;
        }
        final EMFManager emf = EMFManager.getInstance();
        final ETFManager textures = ETFManager.getInstance();
        final boolean parallelEmf = CONTEXT.canBakeEmfInParallel(emf, textures);
        final boolean scopedValidation = parallelEmf && EmfValidationScope.ready(emf);
        prebakedEmfResults = parallelEmf;
        if (parallelEmf) {
            // Warm the reviewed concurrent EMF path before worker threads run.
            EMFResourceCaching.resourceExists(rm, new ResourceLocation("minecraft", "cem/__sunlit_warm__.jem"));
        }

        List<PrebakeTask> work = new ArrayList<>(roots.size());
        for (Map.Entry<ModelLayerLocation, LayerDefinition> entry : roots.entrySet()) {
            if (!isSpecialLayer(entry.getKey())) {
                PrebakeTask task = new PrebakeTask(entry.getKey(), entry.getValue());
                work.add(task);
                PREBAKED.put(entry.getKey(), task);
            }
        }
        if (work.size() < 16) {
            PREBAKED.clear();
            return;
        }

        final PrebakeTask[] tasks = work.toArray(new PrebakeTask[0]);
        final AtomicInteger nextIdx = new AtomicInteger(0);
        final ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        final int workerCount = Math.min(Math.max(2, Runtime.getRuntime().availableProcessors() - 1), 14);
        List<ForkJoinTask<?>> submitted = new ArrayList<>(workerCount);
        for (int w = 0; w < workerCount; w++) {
            submitted.add(ForkJoinPool.commonPool().submit(() -> {
                Thread thread = Thread.currentThread();
                ClassLoader previous = thread.getContextClassLoader();
                if (previous != tccl) {
                    thread.setContextClassLoader(tccl);
                }
                try {
                    int idx;
                    while ((idx = nextIdx.getAndIncrement()) < tasks.length) {
                        PrebakeTask task = tasks[idx];
                        if (!task.claimed.compareAndSet(false, true)) {
                            continue;
                        }
                        try {
                            task.future.complete(runPrebakeTask(task, emf, parallelEmf, scopedValidation));
                        } catch (Throwable ignored) {
                            // Fall back to synchronous Render-thread evaluation for any entry that fails off-thread.
                            task.future.complete(null);
                        }
                    }
                } finally {
                    if (thread.getContextClassLoader() != previous) {
                        thread.setContextClassLoader(previous);
                    }
                }
            }));
        }
        activeWorkers = submitted;
    }

    public static void beginLookup(ModelLayerLocation layer) {
        if (activeWorkers != null && (layer == null || isSpecialLayer(layer) || hasSpecialContext())) {
            awaitActiveWorkers();
        }
        CURRENT_LAYER.set(layer);
        PENDING_EMF_RESULT.remove();
    }

    public static ModelPart takeRawAndStageEmfResult(LayerDefinition def) {
        ModelLayerLocation loc = CURRENT_LAYER.get();
        if (loc != null && !hasSpecialContext()) {
            PrebakeTask task = PREBAKED.remove(loc);
            if (task != null) {
                ModelPart pre = null;
                if (task.claimed.compareAndSet(false, true)) {
                    // Not yet picked up by a worker: wait for no one and bake directly on this thread.
                    try {
                        EMFManager emf = EMFManager.getInstance();
                        boolean parallelEmf = prebakedEmfResults;
                        boolean scopedValidation = parallelEmf && EmfValidationScope.ready(emf);
                        pre = runPrebakeTask(task, emf, parallelEmf, scopedValidation);
                    } catch (Throwable ignored) {
                        pre = null;
                    }
                    task.future.complete(pre);
                } else {
                    try {
                        pre = task.future.join();
                    } catch (Throwable ignored) {
                        pre = null;
                    }
                }
                if (pre != null) {
                    if (prebakedEmfResults) PENDING_EMF_RESULT.set(pre);
                    return pre;
                }
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
