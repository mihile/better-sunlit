package dev.sunlit.bettersunlit.client;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.fml.ModList;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;

/**
 * Precomputes the static registry portion of ModernFix's {@code ModelBakeEventHelper} on a
 * background worker as soon as {@code ModelBakery.<init>} begins, then clones the precomputed
 * fastutil sets when {@code ModelBakeEventHelper.<init>} executes.
 */
public final class ModelBakePerf {
    private static volatile CompletableFuture<PrewarmData> prewarmFuture;
    private static volatile SoftReference<PrewarmData> cachedData = new SoftReference<>(null);
    private static final ThreadLocal<PrewarmData> ACTIVE_APPLY = new ThreadLocal<>();

    private ModelBakePerf() {
    }

    public static void schedulePrewarm() {
        if (cachedData.get() != null) {
            return;
        }
        if (prewarmFuture != null) {
            return;
        }
        synchronized (ModelBakePerf.class) {
            if (cachedData.get() == null && prewarmFuture == null) {
                prewarmFuture = CompletableFuture.supplyAsync(ModelBakePerf::computeStaticData, ForkJoinPool.commonPool());
            }
        }
    }

    public static PrewarmData awaitPrewarm() {
        PrewarmData cached = cachedData.get();
        if (cached != null) {
            return cached;
        }
        CompletableFuture<PrewarmData> future = prewarmFuture;
        if (future == null) {
            return null;
        }
        try {
            PrewarmData data = future.join();
            if (data != null) {
                cachedData = new SoftReference<>(data);
            }
            return data;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static boolean beginIfReady() {
        if (!RenderSystem.isOnRenderThread()) {
            PrewarmData data = awaitPrewarm();
            if (data != null) {
                ACTIVE_APPLY.set(data);
                return true;
            }
            return false;
        }
        PrewarmData data = awaitPrewarm();
        if (data != null) {
            ACTIVE_APPLY.set(data);
            return true;
        }
        return false;
    }

    public static PrewarmData getActivePrewarm() {
        return ACTIVE_APPLY.get();
    }

    public static void finishApply() {
        ACTIVE_APPLY.remove();
    }

    private static final int PARALLEL_BLOCK_CHUNK = 256;
    private static final ConcurrentHashMap<Property<?>, String[]> PROPERTY_OPTIONS = new ConcurrentHashMap<>(512);
    private static final ConcurrentHashMap<PropTupleKey, String[]> VARIANT_COMBO_CACHE = new ConcurrentHashMap<>(512);

    private static final class PropTupleKey {
        private final Property<?>[] props;
        private final int hash;

        PropTupleKey(Property<?>[] props) {
            this.props = props;
            int h = 1;
            for (Property<?> p : props) {
                h = 31 * h + System.identityHashCode(p);
            }
            this.hash = h;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PropTupleKey other) || other.hash != this.hash) {
                return false;
            }
            Property<?>[] a = this.props;
            Property<?>[] b = other.props;
            if (a.length != b.length) {
                return false;
            }
            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private static String[] getPropertyOptions(Property<?> prop) {
        String[] cached = PROPERTY_OPTIONS.get(prop);
        if (cached != null) {
            return cached;
        }
        String propName = prop.m_61708_();
        Collection<? extends Comparable<?>> values = prop.m_6908_();
        String[] out = new String[values.size()];
        int i = 0;
        for (Comparable<?> val : values) {
            out[i++] = (propName + "=" + getPropValueName(prop, val)).toLowerCase(Locale.ROOT);
        }
        String[] prev = PROPERTY_OPTIONS.putIfAbsent(prop, out);
        return prev != null ? prev : out;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String getPropValueName(Property<T> prop, Comparable<?> value) {
        return prop.m_6940_((T) value);
    }

    private static void generateBlockLocations(
            Collection<ResourceLocation> out, Block block, ResourceLocation location, CharScratch scratch) {
        Set<Property<?>> props = block.m_49966_().m_61148_().keySet();
        int propCount = props.size();
        if (propCount == 0) {
            out.add(new ModelResourceLocation(location, ""));
            return;
        }
        if (propCount == 1) {
            String[] opts = getPropertyOptions(props.iterator().next());
            for (int i = 0; i < opts.length; i++) {
                out.add(new ModelResourceLocation(location, opts[i]));
            }
            return;
        }
        Property<?>[] propArr = props.toArray(new Property<?>[propCount]);
        PropTupleKey key = new PropTupleKey(propArr);
        String[] cachedCombos = VARIANT_COMBO_CACHE.get(key);
        if (cachedCombos == null) {
            String[][] optArrays = new String[propCount][];
            int totalStates = 1;
            int maxLen = propCount - 1;
            for (int idx = 0; idx < propCount; idx++) {
                String[] opts = getPropertyOptions(propArr[idx]);
                optArrays[idx] = opts;
                totalStates *= opts.length;
                int optMax = 0;
                for (int i = 0; i < opts.length; i++) {
                    int len = opts[i].length();
                    if (len > optMax) {
                        optMax = len;
                    }
                }
                maxLen += optMax;
            }
            String[] built = new String[totalStates];
            char[] buf = scratch.ensure(maxLen);
            emitPropertyCombinations(built, 0, optArrays, 0, buf, 0);
            String[] prev = VARIANT_COMBO_CACHE.putIfAbsent(key, built);
            cachedCombos = prev != null ? prev : built;
        }
        for (int i = 0; i < cachedCombos.length; i++) {
            out.add(new ModelResourceLocation(location, cachedCombos[i]));
        }
    }

    private static int emitPropertyCombinations(
            String[] out,
            int writeIdx,
            String[][] optArrays,
            int axis,
            char[] buf,
            int pos) {
        String[] opts = optArrays[axis];
        if (axis > 0) {
            buf[pos++] = ',';
        }
        if (axis == optArrays.length - 1) {
            for (int i = 0; i < opts.length; i++) {
                String opt = opts[i];
                int len = opt.length();
                opt.getChars(0, len, buf, pos);
                out[writeIdx++] = new String(buf, 0, pos + len);
            }
            return writeIdx;
        }
        for (int i = 0; i < opts.length; i++) {
            String opt = opts[i];
            int len = opt.length();
            opt.getChars(0, len, buf, pos);
            writeIdx = emitPropertyCombinations(out, writeIdx, optArrays, axis + 1, buf, pos + len);
        }
        return writeIdx;
    }

    private static final class CharScratch {
        private char[] buf = new char[256];

        char[] ensure(int minLen) {
            if (buf.length < minLen) {
                buf = new char[Math.max(minLen, buf.length << 1)];
            }
            return buf;
        }
    }

    public static PrewarmData computeStaticData() {
        int stateCount = 0;
        for (Block block : BuiltInRegistries.f_256975_) {
            stateCount += block.m_49965_().m_61056_().size();
        }
        ObjectLinkedOpenHashSet<ResourceLocation> topLevel = new ObjectLinkedOpenHashSet<>(
                stateCount + BuiltInRegistries.f_257033_.m_13562_());
        ObjectOpenHashSet<String> namespaces = new ObjectOpenHashSet<>(ModList.get().size());
        List<Map.Entry<ResourceKey<Block>, Block>> blockEntries =
                new ArrayList<>(BuiltInRegistries.f_256975_.m_6579_());
        int totalBlocks = blockEntries.size();
        if (totalBlocks <= PARALLEL_BLOCK_CHUNK) {
            CharScratch scratch = new CharScratch();
            for (int i = 0; i < totalBlocks; i++) {
                Map.Entry<ResourceKey<Block>, Block> entry = blockEntries.get(i);
                ResourceLocation location = entry.getKey().m_135782_();
                generateBlockLocations(topLevel, entry.getValue(), location, scratch);
                namespaces.add(location.m_135827_());
            }
        } else {
            int chunkCount = (totalBlocks + PARALLEL_BLOCK_CHUNK - 1) / PARALLEL_BLOCK_CHUNK;
            @SuppressWarnings("unchecked")
            ArrayList<ResourceLocation>[] chunkLocs = new ArrayList[chunkCount];
            @SuppressWarnings("unchecked")
            ObjectOpenHashSet<String>[] chunkNamespaces = new ObjectOpenHashSet[chunkCount];
            List<CompletableFuture<Void>> futures = new ArrayList<>(chunkCount);
            for (int c = 0; c < chunkCount; c++) {
                final int chunkIndex = c;
                final int from = c * PARALLEL_BLOCK_CHUNK;
                final int to = Math.min(totalBlocks, from + PARALLEL_BLOCK_CHUNK);
                futures.add(CompletableFuture.runAsync(() -> {
                    int localStates = 0;
                    for (int i = from; i < to; i++) {
                        localStates += blockEntries.get(i).getValue().m_49965_().m_61056_().size();
                    }
                    ArrayList<ResourceLocation> localLocs = new ArrayList<>(localStates);
                    ObjectOpenHashSet<String> localNs = new ObjectOpenHashSet<>();
                    CharScratch scratch = new CharScratch();
                    for (int i = from; i < to; i++) {
                        Map.Entry<ResourceKey<Block>, Block> entry = blockEntries.get(i);
                        ResourceLocation location = entry.getKey().m_135782_();
                        generateBlockLocations(localLocs, entry.getValue(), location, scratch);
                        localNs.add(location.m_135827_());
                    }
                    chunkLocs[chunkIndex] = localLocs;
                    chunkNamespaces[chunkIndex] = localNs;
                }, ForkJoinPool.commonPool()));
            }
            for (int c = 0; c < chunkCount; c++) {
                futures.get(c).join();
            }
            for (int c = 0; c < chunkCount; c++) {
                ArrayList<ResourceLocation> locs = chunkLocs[c];
                for (int i = 0, n = locs.size(); i < n; i++) {
                    topLevel.add(locs.get(i));
                }
                for (String ns : chunkNamespaces[c]) {
                    namespaces.add(ns);
                }
            }
        }
        for (ResourceLocation itemLocation : BuiltInRegistries.f_257033_.m_6566_()) {
            topLevel.add(new ModelResourceLocation(itemLocation, "inventory"));
            namespaces.add(itemLocation.m_135827_());
        }
        VARIANT_COMBO_CACHE.clear();
        return new PrewarmData(topLevel, namespaces);
    }

    static void resetForTest() {
        synchronized (ModelBakePerf.class) {
            prewarmFuture = null;
            cachedData = new SoftReference<>(null);
            ACTIVE_APPLY.remove();
            VARIANT_COMBO_CACHE.clear();
        }
    }

    public static final class PrewarmData {
        public final ObjectLinkedOpenHashSet<ResourceLocation> topLevelModelLocations;
        public final ObjectOpenHashSet<String> namespacesWithModels;
        private boolean claimed;

        public PrewarmData(
                ObjectLinkedOpenHashSet<ResourceLocation> topLevelModelLocations,
                ObjectOpenHashSet<String> namespacesWithModels) {
            this.topLevelModelLocations = topLevelModelLocations;
            this.namespacesWithModels = namespacesWithModels;
        }

        public synchronized ObjectLinkedOpenHashSet<ResourceLocation> claimTopLevel() {
            if (!claimed) {
                return topLevelModelLocations;
            }
            return topLevelModelLocations.clone();
        }

        public synchronized ObjectOpenHashSet<String> claimNamespaces() {
            if (!claimed) {
                claimed = true;
                return namespacesWithModels;
            }
            return namespacesWithModels.clone();
        }
    }
}
