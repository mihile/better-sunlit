package dev.sunlit.bettersunlit.client;

import de.odysseus.ithaka.digraph.Digraph;
import de.odysseus.ithaka.digraph.Digraphs;
import de.odysseus.ithaka.digraph.MapDigraph;
import de.odysseus.ithaka.digraph.util.fas.FeedbackArcSet;
import de.odysseus.ithaka.digraph.util.fas.FeedbackArcSetPolicy;
import de.odysseus.ithaka.digraph.util.fas.FeedbackArcSetProvider;
import it.unimi.dsi.fastutil.ints.IntArrays;
import net.irisshaders.batchedentityrendering.impl.TransparencyType;
import net.minecraft.client.renderer.RenderType;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Random;
import de.odysseus.ithaka.digraph.util.fas.SimpleFeedbackArcSetProvider;
import java.util.Set;

/**
 * Pooled render-type graphs, allocation-free DAG ordering, and cached native cyclic ordering
 * for {@code GraphTranslucencyRenderOrderManager}.
 */
public final class GraphOrderPerf {

    private static final TransparencyType[] TRANSPARENCY_TYPES = TransparencyType.values();
    private static final int LINEAR_SCAN_THRESHOLD = 12;

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private GraphOrderPerf() {
    }

    public static void initPooled(EnumMap<TransparencyType, Digraph<RenderType>> types) {
        for (TransparencyType type : TRANSPARENCY_TYPES) {
            types.put(type, new PooledRenderDigraph<>());
        }
    }

    public static void resetAll(EnumMap<TransparencyType, Digraph<RenderType>> types) {
        for (TransparencyType type : TRANSPARENCY_TYPES) {
            resetOne(types, type);
        }
    }

    public static void resetOne(EnumMap<TransparencyType, Digraph<RenderType>> types, TransparencyType type) {
        Digraph<RenderType> graph = types.get(type);
        if (graph instanceof PooledRenderDigraph<?> pooled) {
            if (pooled.vertexCount > 0) {
                pooled.clear();
            }
        } else if (graph == null || graph.getVertexCount() > 0) {
            types.put(type, new PooledRenderDigraph<>());
        }
    }

    public static void recordBegin(
            EnumMap<TransparencyType, Digraph<RenderType>> types,
            EnumMap<TransparencyType, RenderType> currentTypes,
            TransparencyType transparencyType,
            RenderType type,
            boolean inGroup) {
        Digraph<RenderType> graph = types.get(transparencyType);
        if (graph instanceof PooledRenderDigraph<?> rawPooled) {
            @SuppressWarnings("unchecked")
            PooledRenderDigraph<RenderType> pooled = (PooledRenderDigraph<RenderType>) rawPooled;
            int nextIdx = pooled.addVertexIndex(type);
            if (inGroup) {
                RenderType previous = currentTypes.put(transparencyType, type);
                if (previous != null) {
                    int prevIdx = pooled.addVertexIndex(previous);
                    pooled.incrementEdgeByIndex(prevIdx, nextIdx);
                }
            }
            return;
        }
        graph.add(type);
        if (inGroup) {
            RenderType previous = currentTypes.put(transparencyType, type);
            if (previous == null) {
                return;
            }
            int weight = graph.get(previous, type).orElse(0) + 1;
            graph.put(previous, type, weight);
        }
    }

    /** The returned list belongs to the caller; only graph/sort working storage is pooled. */
    public static List<RenderType> getRenderOrder(
            EnumMap<TransparencyType, Digraph<RenderType>> types,
            FeedbackArcSetProvider provider) {
        int count = 0;
        for (TransparencyType t : TRANSPARENCY_TYPES) {
            Digraph<RenderType> g = types.get(t);
            if (g != null) count += g.getVertexCount();
        }
        if (count == 0) return Collections.emptyList();
        List<RenderType> result = new ArrayList<>(count);
        Scratch scratch = SCRATCH.get();
        for (TransparencyType t : TRANSPARENCY_TYPES) {
            Digraph<RenderType> graph = types.get(t);
            if (graph == null || graph.getVertexCount() == 0) continue;
            if (graph instanceof PooledRenderDigraph<?> raw) {
                @SuppressWarnings("unchecked")
                PooledRenderDigraph<RenderType> pooled = (PooledRenderDigraph<RenderType>) raw;
                orderPooledGraph(pooled, result, scratch, provider);
            } else {
                orderFallbackGraph(graph, provider, result);
            }
        }
        return result;
    }

    private static <V> void orderFallbackGraph(Digraph<V> graph, FeedbackArcSetProvider provider, List<V> out) {
        FeedbackArcSet<V> fas = provider.getFeedbackArcSet(graph, graph, FeedbackArcSetPolicy.MIN_WEIGHT);
        for (V src : fas.vertices()) for (V dst : fas.targets(src)) graph.remove(src, dst);
        out.addAll(Digraphs.toposort(graph, false));
    }

    public static <V> void orderPooledGraph(PooledRenderDigraph<V> graph, List<V> out) {
        Scratch scratch = SCRATCH.get();
        orderPooledGraph(graph, out, scratch, scratch.provider);
    }

    static <V> void orderPooledGraph(PooledRenderDigraph<V> graph, List<V> out,
                                    Scratch scratch, FeedbackArcSetProvider provider) {
        int n = graph.vertexCount;
        if (n == 0) return;
        scratch.ensureVertexCap(n);
        if (n == 1 || !hasNonSelfCycle(graph, scratch)) {
            appendToposort(graph, out, scratch);
            return;
        }
        if (provider != null && provider.getClass() == SimpleFeedbackArcSetProvider.class
                && tryOrderSimpleCycleFast(graph, out, scratch)) {
            return;
        }
        // Preserve native MIN_WEIGHT decisions and insertion-order tie breaking. Reuse the
        // result across up to 8 cyclic subgraphs (e.g., shadow vs main pass) when exact match hits.
        OrderCache hit = null;
        OrderCache[] caches = graph.orderCaches;
        for (int i = 0; i < PooledRenderDigraph.ORDER_CACHE_SLOTS; i++) {
            OrderCache cand = caches[i];
            if (cand != null && cand.matches(graph, provider)) {
                hit = cand;
                break;
            }
        }
        if (hit == null) {
            int slot = graph.orderCacheCursor;
            graph.orderCacheCursor = (slot + 1) & (PooledRenderDigraph.ORDER_CACHE_SLOTS - 1);
            hit = caches[slot];
            if (hit == null) {
                hit = new OrderCache();
                caches[slot] = hit;
            }
            hit.compute(graph, provider, scratch);
        }
        hit.apply(graph, out);
    }

    private static <V> boolean tryOrderSimpleCycleFast(PooledRenderDigraph<V> g, List<V> out, Scratch s) {
        int n = g.vertexCount;
        if (n == 2) {
            int slot01 = findEdgeSlot(g, 0, 1);
            int slot10 = findEdgeSlot(g, 1, 0);
            if (slot01 < 0 || slot10 < 0) return false;
            int w01 = g.outWeights[0][slot01];
            int w10 = g.outWeights[1][slot10];
            if (w01 == w10) return false;
            if (w01 < w10) {
                g.removeEdgeSlot(0, slot01);
            } else {
                g.removeEdgeSlot(1, slot10);
            }
            appendToposort(g, out, s);
            return true;
        }
        if (n > 64) return false;
        int[] partner = s.partner;
        int[] droppedSlot = s.droppedSlot;
        Arrays.fill(partner, 0, n, -1);
        Arrays.fill(droppedSlot, 0, n, -1);
        int pairCount = 0;

        for (int u = 0; u < n; u++) {
            int deg = g.outDegree[u];
            int[] targets = g.outTargets[u];
            for (int e = 0; e < deg; e++) {
                int v = targets[e];
                if (v <= u) continue;
                int backSlot = findEdgeSlot(g, v, u);
                if (backSlot < 0) continue;
                int wUV = g.outWeights[u][e];
                int wVU = g.outWeights[v][backSlot];
                if (wUV == wVU || partner[u] != -1 || partner[v] != -1) {
                    return false;
                }
                partner[u] = v;
                partner[v] = u;
                if (wUV < wVU) {
                    droppedSlot[u] = e;
                } else {
                    droppedSlot[v] = backSlot;
                }
                pairCount++;
            }
        }
        if (pairCount == 0 || hasQuotientCycle(g, partner, s)) {
            return false;
        }
        for (int u = 0; u < n; u++) {
            if (droppedSlot[u] >= 0) {
                g.removeEdgeSlot(u, droppedSlot[u]);
            }
        }
        appendToposort(g, out, s);
        return true;
    }

    private static <V> boolean hasQuotientCycle(PooledRenderDigraph<V> g, int[] partner, Scratch s) {
        int n = g.vertexCount;
        int[] color = s.color;
        Arrays.fill(color, 0, n, 0);
        for (int start = 0; start < n; start++) {
            int repStart = (partner[start] >= 0 && partner[start] < start) ? partner[start] : start;
            if (start == repStart && color[repStart] == 0 && quotientDfs(g, repStart, partner, color)) {
                return true;
            }
        }
        return false;
    }

    private static <V> boolean quotientDfs(PooledRenderDigraph<V> g, int repU, int[] partner, int[] color) {
        color[repU] = 1;
        if (scanQuotientEdges(g, repU, repU, partner, color)) {
            return true;
        }
        int p = partner[repU];
        if (p >= 0 && scanQuotientEdges(g, p, repU, partner, color)) {
            return true;
        }
        color[repU] = 2;
        return false;
    }

    private static <V> boolean scanQuotientEdges(PooledRenderDigraph<V> g, int u, int repU, int[] partner, int[] color) {
        int deg = g.outDegree[u];
        int[] targets = g.outTargets[u];
        for (int i = 0; i < deg; i++) {
            int v = targets[i];
            int repV = (partner[v] >= 0 && partner[v] < v) ? partner[v] : v;
            if (repV == repU) continue;
            int c = color[repV];
            if (c == 1) return true;
            if (c == 0 && quotientDfs(g, repV, partner, color)) return true;
        }
        return false;
    }

    private static <V> int findEdgeSlot(PooledRenderDigraph<V> g, int uIdx, int vIdx) {
        int deg = g.outDegree[uIdx];
        int[] targets = g.outTargets[uIdx];
        for (int i = 0; i < deg; i++) {
            if (targets[i] == vIdx) return i;
        }
        return -1;
    }

    private static <V> boolean hasNonSelfCycle(PooledRenderDigraph<V> g, Scratch s) {
        int n = g.vertexCount;
        int[] color = s.color;
        int[] dfsNode = s.dfsNode;
        int[] dfsEdge = s.dfsEdge;
        Arrays.fill(color, 0, n, 0);

        for (int start = 0; start < n; start++) {
            if (color[start] != 0) continue;
            int sp = 0;
            color[start] = 1;
            dfsNode[0] = start;
            dfsEdge[0] = 0;

            while (sp >= 0) {
                int u = dfsNode[sp];
                int ei = dfsEdge[sp];
                int deg = g.outDegree[u];
                if (ei < deg) {
                    dfsEdge[sp] = ei + 1;
                    int v = g.outTargets[u][ei];
                    if (v == u) continue;
                    int vc = color[v];
                    if (vc == 1) return true;
                    if (vc == 0) {
                        color[v] = 1;
                        sp++;
                        dfsNode[sp] = v;
                        dfsEdge[sp] = 0;
                    }
                } else {
                    color[u] = 2;
                    sp--;
                }
            }
        }
        return false;
    }

    private static <V> void appendToposort(PooledRenderDigraph<V> g, List<V> out, Scratch s) {
        int n = g.vertexCount;
        s.ensureVertexCap(n);
        int[] color = s.color;
        int[] dfsNode = s.dfsNode;
        int[] dfsEdge = s.dfsEdge;
        int[] order = s.postOrder;
        Arrays.fill(color, 0, n, 0);

        int count = 0;
        for (int start = 0; start < n; start++) {
            if (color[start] != 0) {
                continue;
            }
            int sp = 0;
            color[start] = 1;
            dfsNode[0] = start;
            dfsEdge[0] = 0;

            while (sp >= 0) {
                int u = dfsNode[sp];
                int ei = dfsEdge[sp];
                int deg = g.outDegree[u];
                if (ei < deg) {
                    dfsEdge[sp] = ei + 1;
                    int v = g.outTargets[u][ei];
                    if (v != u && color[v] == 0) {
                        color[v] = 1;
                        sp++;
                        dfsNode[sp] = v;
                        dfsEdge[sp] = 0;
                    }
                } else {
                    color[u] = 2;
                    order[count++] = u;
                    sp--;
                }
            }
        }

        // Digraphs.toposort(g, false) reverses finishing post-order.
        Object[] verts = g.vertices;
        for (int i = count - 1; i >= 0; i--) {
            @SuppressWarnings("unchecked")
            V v = (V) verts[order[i]];
            out.add(v);
        }
    }

    public static final class PooledRenderDigraph<V> implements Digraph<V> {
        private static final int INITIAL_VERTEX_CAP = 16;
        private static final int INITIAL_EDGE_CAP = 8;

        Object[] vertices = new Object[INITIAL_VERTEX_CAP];
        int[] outDegree = new int[INITIAL_VERTEX_CAP];
        int[][] outTargets = new int[INITIAL_VERTEX_CAP][];
        int[][] outWeights = new int[INITIAL_VERTEX_CAP][];
        int vertexCount;
        int edgeCount;
        static final int ORDER_CACHE_SLOTS = 8;
        final OrderCache[] orderCaches = new OrderCache[ORDER_CACHE_SLOTS];
        int orderCacheCursor;

        // Open-addressing hash map used only when vertexCount exceeds LINEAR_SCAN_THRESHOLD.
        private Object[] hashKeys;
        private int[] hashIndices; // stores vertexIndex + 1, 0 == empty
        private int hashMask;
        private boolean hashPopulated;

        public void clear() {
            if (vertexCount > 0) {
                Arrays.fill(vertices, 0, vertexCount, null);
                Arrays.fill(outDegree, 0, vertexCount, 0);
            }
            if (hashPopulated && hashKeys != null) {
                Arrays.fill(hashKeys, null);
                Arrays.fill(hashIndices, 0);
                hashPopulated = false;
            }
            vertexCount = 0;
            edgeCount = 0;
        }

        int addVertexIndex(V v) {
            Objects.requireNonNull(v, "vertex");
            int existing = indexOf(v);
            if (existing >= 0) {
                return existing;
            }
            int idx = vertexCount;
            if (idx == vertices.length) {
                growVertexArrays();
            }
            vertices[idx] = v;
            outDegree[idx] = 0;
            if (idx + 1 > LINEAR_SCAN_THRESHOLD) {
                ensureHashReady();
                insertHashEntry(v, idx);
            }
            vertexCount = idx + 1;
            return idx;
        }

        void incrementEdgeByIndex(int uIdx, int vIdx) {
            int deg = outDegree[uIdx];
            int[] targets = outTargets[uIdx];
            int[] weights = outWeights[uIdx];
            if (targets != null) {
                for (int i = 0; i < deg; i++) {
                    if (targets[i] == vIdx) {
                        weights[i] = weights[i] + 1;
                        return;
                    }
                }
            }
            addNewEdgeSlot(uIdx, vIdx, 1);
        }

        private void addNewEdgeSlot(int uIdx, int vIdx, int weight) {
            int deg = outDegree[uIdx];
            int[] targets = outTargets[uIdx];
            int[] weights = outWeights[uIdx];
            if (targets == null) {
                targets = new int[INITIAL_EDGE_CAP];
                weights = new int[INITIAL_EDGE_CAP];
                outTargets[uIdx] = targets;
                outWeights[uIdx] = weights;
            } else if (deg == targets.length) {
                int nextCap = targets.length << 1;
                targets = Arrays.copyOf(targets, nextCap);
                weights = Arrays.copyOf(weights, nextCap);
                outTargets[uIdx] = targets;
                outWeights[uIdx] = weights;
            }
            targets[deg] = vIdx;
            weights[deg] = weight;
            outDegree[uIdx] = deg + 1;
            edgeCount++;
        }

        boolean hasEdgeIndex(int uIdx, int vIdx) {
            int deg = outDegree[uIdx];
            int[] targets = outTargets[uIdx];
            for (int i = 0; i < deg; i++) {
                if (targets[i] == vIdx) {
                    return true;
                }
            }
            return false;
        }

        void removeEdgeSlot(int uIdx, int slot) {
            int last = --outDegree[uIdx];
            if (slot != last) {
                System.arraycopy(outTargets[uIdx], slot + 1, outTargets[uIdx], slot, last - slot);
                System.arraycopy(outWeights[uIdx], slot + 1, outWeights[uIdx], slot, last - slot);
            }
            edgeCount--;
        }

        private int indexOf(Object v) {
            if (v == null) {
                return -1;
            }
            if (hashPopulated) {
                return findInHash(v);
            }
            for (int i = 0; i < vertexCount; i++) {
                Object cand = vertices[i];
                if (cand == v || cand.equals(v)) {
                    return i;
                }
            }
            return -1;
        }

        private void growVertexArrays() {
            int newCap = vertices.length << 1;
            vertices = Arrays.copyOf(vertices, newCap);
            outDegree = Arrays.copyOf(outDegree, newCap);
            outTargets = Arrays.copyOf(outTargets, newCap);
            outWeights = Arrays.copyOf(outWeights, newCap);
        }

        private void ensureHashReady() {
            if (!hashPopulated) {
                int cap = Math.max(32, Integer.highestOneBit(Math.max(16, vertexCount * 2) - 1) << 1);
                if (hashKeys == null || hashKeys.length < cap) {
                    hashKeys = new Object[cap];
                    hashIndices = new int[cap];
                    hashMask = cap - 1;
                }
                for (int i = 0; i < vertexCount; i++) {
                    insertHashEntry(vertices[i], i);
                }
                hashPopulated = true;
            } else if ((vertexCount + 1) * 2 >= hashKeys.length) {
                int newCap = hashKeys.length << 1;
                hashKeys = new Object[newCap];
                hashIndices = new int[newCap];
                hashMask = newCap - 1;
                for (int i = 0; i < vertexCount; i++) {
                    insertHashEntry(vertices[i], i);
                }
            }
        }

        private void insertHashEntry(Object key, int idx) {
            int pos = mixHash(key.hashCode()) & hashMask;
            while (hashKeys[pos] != null) {
                pos = (pos + 1) & hashMask;
            }
            hashKeys[pos] = key;
            hashIndices[pos] = idx + 1;
        }

        private int findInHash(Object key) {
            int pos = mixHash(key.hashCode()) & hashMask;
            while (true) {
                Object k = hashKeys[pos];
                if (k == null) {
                    return -1;
                }
                if (k == key || k.equals(key)) {
                    return hashIndices[pos] - 1;
                }
                pos = (pos + 1) & hashMask;
            }
        }

        private void rebuildHashAfterRemoval() {
            if (hashKeys != null && hashPopulated) {
                Arrays.fill(hashKeys, null);
                Arrays.fill(hashIndices, 0);
                if (vertexCount <= LINEAR_SCAN_THRESHOLD) {
                    hashPopulated = false;
                } else {
                    for (int i = 0; i < vertexCount; i++) {
                        insertHashEntry(vertices[i], i);
                    }
                }
            }
        }

        private static int mixHash(int h) {
            h ^= (h >>> 16);
            return h;
        }

        @Override
        public OptionalInt get(V source, V target) {
            int u = indexOf(source);
            int v = indexOf(target);
            if (u < 0 || v < 0) {
                return OptionalInt.empty();
            }
            int deg = outDegree[u];
            int[] targets = outTargets[u];
            for (int i = 0; i < deg; i++) {
                if (targets[i] == v) {
                    return OptionalInt.of(outWeights[u][i]);
                }
            }
            return OptionalInt.empty();
        }

        @Override
        public boolean contains(V source, V target) {
            int u = indexOf(source);
            int v = indexOf(target);
            return u >= 0 && v >= 0 && hasEdgeIndex(u, v);
        }

        @Override
        public boolean contains(V vertex) {
            return indexOf(vertex) >= 0;
        }

        @Override
        public boolean add(V vertex) {
            Objects.requireNonNull(vertex, "vertex");
            if (indexOf(vertex) >= 0) {
                return false;
            }
            addVertexIndex(vertex);
            return true;
        }

        @Override
        public OptionalInt put(V source, V target, int weight) {
            int u = addVertexIndex(source);
            int v = addVertexIndex(target);
            int deg = outDegree[u];
            int[] targets = outTargets[u];
            int[] weights = outWeights[u];
            if (targets != null) {
                for (int i = 0; i < deg; i++) {
                    if (targets[i] == v) {
                        int prev = weights[i];
                        weights[i] = weight;
                        return OptionalInt.of(prev);
                    }
                }
            }
            addNewEdgeSlot(u, v, weight);
            return OptionalInt.empty();
        }

        @Override
        public OptionalInt remove(V source, V target) {
            int u = indexOf(source);
            int v = indexOf(target);
            if (u < 0 || v < 0) {
                return OptionalInt.empty();
            }
            int deg = outDegree[u];
            int[] targets = outTargets[u];
            for (int i = 0; i < deg; i++) {
                if (targets[i] == v) {
                    int prev = outWeights[u][i];
                    removeEdgeSlot(u, i);
                    return OptionalInt.of(prev);
                }
            }
            return OptionalInt.empty();
        }

        @Override
        public boolean remove(V vertex) {
            int rem = indexOf(vertex);
            if (rem < 0) {
                return false;
            }
            edgeCount -= outDegree[rem];
            outDegree[rem] = 0;

            // Remove incoming edges pointing to rem, and remap last vertex into rem if needed.
            int last = vertexCount - 1;
            for (int u = 0; u < vertexCount; u++) {
                if (u == rem) {
                    continue;
                }
                int[] targets = outTargets[u];
                for (int i = outDegree[u] - 1; i >= 0; i--) {
                    if (targets[i] == rem) {
                        removeEdgeSlot(u, i);
                    }
                }
            }

            if (rem != last) {
                vertices[rem] = vertices[last];
                outDegree[rem] = outDegree[last];
                int[] tmpTargets = outTargets[rem];
                int[] tmpWeights = outWeights[rem];
                outTargets[rem] = outTargets[last];
                outWeights[rem] = outWeights[last];
                outTargets[last] = tmpTargets;
                outWeights[last] = tmpWeights;
                outDegree[last] = 0;
                // Re-point any remaining edge references from last -> rem.
                for (int u = 0; u < last; u++) {
                    int deg = outDegree[u];
                    int[] targets = outTargets[u];
                    for (int i = 0; i < deg; i++) {
                        if (targets[i] == last) {
                            targets[i] = rem;
                        }
                    }
                }
            }
            vertices[last] = null;
            vertexCount = last;
            rebuildHashAfterRemoval();
            return true;
        }

        @Override
        public void removeAll(Collection<V> verticesToRemove) {
            for (V v : verticesToRemove) {
                remove(v);
            }
        }

        @Override
        public Iterable<V> vertices() {
            return new AbstractCollection<V>() {
                @Override
                public Iterator<V> iterator() {
                    return new Iterator<V>() {
                        int idx;

                        @Override
                        public boolean hasNext() {
                            return idx < vertexCount;
                        }

                        @SuppressWarnings("unchecked")
                        @Override
                        public V next() {
                            if (idx >= vertexCount) {
                                throw new NoSuchElementException();
                            }
                            return (V) vertices[idx++];
                        }
                    };
                }

                @Override
                public int size() {
                    return vertexCount;
                }
            };
        }

        @Override
        public Iterable<V> targets(V source) {
            int u = indexOf(source);
            if (u < 0 || outDegree[u] == 0) {
                return Collections.emptyList();
            }
            return new Iterable<V>() {
                @Override
                public Iterator<V> iterator() {
                    return new Iterator<V>() {
                        int ei;

                        @Override
                        public boolean hasNext() {
                            return ei < outDegree[u];
                        }

                        @SuppressWarnings("unchecked")
                        @Override
                        public V next() {
                            if (ei >= outDegree[u]) {
                                throw new NoSuchElementException();
                            }
                            return (V) vertices[outTargets[u][ei++]];
                        }
                    };
                }
            };
        }

        @Override
        public int getVertexCount() {
            return vertexCount;
        }

        @Override
        public int totalWeight() {
            int sum = 0;
            for (int u = 0; u < vertexCount; u++) {
                int deg = outDegree[u];
                int[] w = outWeights[u];
                for (int i = 0; i < deg; i++) {
                    sum += w[i];
                }
            }
            return sum;
        }

        @Override
        public int getOutDegree(V vertex) {
            int u = indexOf(vertex);
            return u < 0 ? 0 : outDegree[u];
        }

        @Override
        public int getEdgeCount() {
            return edgeCount;
        }

        @Override
        public boolean isAcyclic() {
            if (vertexCount < 2) {
                return true;
            }
            Scratch scratch = SCRATCH.get();
            scratch.ensureVertexCap(vertexCount);
            return !hasNonSelfCycle(this, scratch);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Digraph<V> reverse() {
            MapDigraph<V> rev = new MapDigraph<>();
            for (int u = 0; u < vertexCount; u++) {
                V src = (V) vertices[u];
                rev.add(src);
            }
            for (int u = 0; u < vertexCount; u++) {
                V src = (V) vertices[u];
                int deg = outDegree[u];
                int[] targets = outTargets[u];
                int[] weights = outWeights[u];
                for (int i = 0; i < deg; i++) {
                    rev.put((V) vertices[targets[i]], src, weights[i]);
                }
            }
            return rev;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Digraph<V> subgraph(Set<V> subset) {
            MapDigraph<V> sub = new MapDigraph<>();
            for (V v : subset) {
                if (contains(v)) {
                    sub.add(v);
                }
            }
            for (V uVert : subset) {
                int u = indexOf(uVert);
                if (u < 0) {
                    continue;
                }
                int deg = outDegree[u];
                int[] targets = outTargets[u];
                int[] weights = outWeights[u];
                for (int i = 0; i < deg; i++) {
                    V dst = (V) vertices[targets[i]];
                    if (subset.contains(dst)) {
                        sub.put(uVert, dst, weights[i]);
                    }
                }
            }
            return sub;
        }
    }

    private static final class OrderCache {
        private int cachedVertexCount;
        private Object[] vertices = new Object[0];
        private int[] edgeCounts = IntArrays.EMPTY_ARRAY;
        private int[][] targets = new int[0][];
        private int[][] weights = new int[0][];
        private int[] keptEdgeCounts = IntArrays.EMPTY_ARRAY;
        private int[][] keptTargets = new int[0][];
        private int[][] keptWeights = new int[0][];
        private Object[] order = new Object[0];
        private int cachedOrderCount;
        private FeedbackArcSetProvider owner;
        private boolean valid;

        private void ensureVertexStorage(int n) {
            if (vertices.length < n) {
                int cap = Math.max(n, Math.max(8, vertices.length * 2));
                int oldLen = vertices.length;
                vertices = Arrays.copyOf(vertices, cap);
                edgeCounts = Arrays.copyOf(edgeCounts, cap);
                targets = Arrays.copyOf(targets, cap);
                weights = Arrays.copyOf(weights, cap);
                keptEdgeCounts = Arrays.copyOf(keptEdgeCounts, cap);
                keptTargets = Arrays.copyOf(keptTargets, cap);
                keptWeights = Arrays.copyOf(keptWeights, cap);
                order = Arrays.copyOf(order, cap);
                for (int u = oldLen; u < cap; u++) {
                    targets[u] = IntArrays.EMPTY_ARRAY;
                    weights[u] = IntArrays.EMPTY_ARRAY;
                    keptTargets[u] = IntArrays.EMPTY_ARRAY;
                    keptWeights[u] = IntArrays.EMPTY_ARRAY;
                }
            }
        }

        private void snapshotGraph(PooledRenderDigraph<?> graph, int n) {
            ensureVertexStorage(n);
            for (int u = 0; u < n; u++) {
                vertices[u] = graph.vertices[u];
                int deg = graph.outDegree[u];
                edgeCounts[u] = deg;
                if (deg > 0) {
                    if (targets[u].length < deg) {
                        int rowCap = Math.max(deg, Math.max(4, targets[u].length * 2));
                        targets[u] = new int[rowCap];
                        weights[u] = new int[rowCap];
                        keptTargets[u] = new int[rowCap];
                        keptWeights[u] = new int[rowCap];
                    } else if (keptTargets[u].length < deg) {
                        int rowCap = targets[u].length;
                        keptTargets[u] = new int[rowCap];
                        keptWeights[u] = new int[rowCap];
                    }
                    System.arraycopy(graph.outTargets[u], 0, targets[u], 0, deg);
                    System.arraycopy(graph.outWeights[u], 0, weights[u], 0, deg);
                }
            }
            if (cachedVertexCount > n) {
                Arrays.fill(vertices, n, cachedVertexCount, null);
            }
            cachedVertexCount = n;
        }

        boolean matches(PooledRenderDigraph<?> graph, FeedbackArcSetProvider provider) {
            if (!valid || owner != provider || cachedVertexCount != graph.vertexCount) return false;
            for (int u = 0; u < cachedVertexCount; u++) {
                int deg = graph.outDegree[u];
                if (vertices[u] != graph.vertices[u] || edgeCounts[u] != deg) return false;
                int[] t = targets[u], w = weights[u];
                int[] gt = graph.outTargets[u], gw = graph.outWeights[u];
                for (int e = 0; e < deg; e++) {
                    if (t[e] != gt[e] || w[e] != gw[e]) return false;
                }
            }
            return true;
        }

        <V> void compute(PooledRenderDigraph<V> graph, FeedbackArcSetProvider provider, Scratch scratch) {
            valid = false;
            int n = graph.vertexCount;
            snapshotGraph(graph, n);
            if (provider != null && provider.getClass() == SimpleFeedbackArcSetProvider.class
                    && computeSimpleFas(n, scratch)) {
                owner = provider;
                valid = true;
                return;
            }
            computeFallback(n, provider, scratch);
            owner = provider;
            valid = true;
        }

        private boolean computeSimpleFas(int n, Scratch s) {
            s.ensureVertexCap(n);
            int[] edgeBase = s.edgeBase;
            edgeBase[0] = 0;
            for (int u = 0; u < n; u++) {
                edgeBase[u + 1] = edgeBase[u] + edgeCounts[u];
            }
            int totalEdges = edgeBase[n];
            s.ensureEdgeCap(totalEdges);
            if (totalEdges > 0) {
                Arrays.fill(s.edgeRemoved, 0, totalEdges, (byte) 0);
            }

            // Kosaraju SCC matching Digraphs.scc(reference) using zero-allocation CSR + arrays.
            Arrays.fill(s.sccVisited, 0, n, (byte) 0);
            int finishCount = 0;
            for (int start = 0; start < n; start++) {
                if (s.sccVisited[start] != 0) continue;
                int sp = 0;
                s.sccVisited[start] = 1;
                s.dfsNode[0] = start;
                s.dfsEdge[0] = 0;
                while (sp >= 0) {
                    int u = s.dfsNode[sp];
                    int ei = s.dfsEdge[sp];
                    int deg = edgeCounts[u];
                    if (ei < deg) {
                        s.dfsEdge[sp] = ei + 1;
                        int v = targets[u][ei];
                        if (s.sccVisited[v] == 0) {
                            s.sccVisited[v] = 1;
                            sp++;
                            s.dfsNode[sp] = v;
                            s.dfsEdge[sp] = 0;
                        }
                    } else {
                        s.finishStack[finishCount++] = u;
                        sp--;
                    }
                }
            }

            Arrays.fill(s.revDegree, 0, n, 0);
            for (int u = 0; u < n; u++) {
                int deg = edgeCounts[u];
                int[] t = targets[u];
                for (int e = 0; e < deg; e++) {
                    s.revDegree[t[e]]++;
                }
            }
            s.revHead[0] = 0;
            for (int v = 0; v < n; v++) {
                int head = s.revHead[v];
                s.revHead[v + 1] = head + s.revDegree[v];
                s.revCursor[v] = head;
            }
            for (int u = 0; u < n; u++) {
                int deg = edgeCounts[u];
                int[] t = targets[u];
                for (int e = 0; e < deg; e++) {
                    int v = t[e];
                    s.revTargets[s.revCursor[v]++] = u;
                }
            }

            Arrays.fill(s.sccVisited, 0, n, (byte) 0);
            s.sccOffsets[0] = 0;
            int sccCount = 0;
            int sccTotalVertices = 0;
            for (int fi = finishCount - 1; fi >= 0; fi--) {
                int root = s.finishStack[fi];
                if (s.sccVisited[root] != 0) continue;
                int sp = 0;
                s.sccVisited[root] = 1;
                s.dfsNode[0] = root;
                s.dfsEdge[0] = s.revHead[root];
                while (sp >= 0) {
                    int u = s.dfsNode[sp];
                    int ei = s.dfsEdge[sp];
                    int end = s.revHead[u + 1];
                    if (ei < end) {
                        s.dfsEdge[sp] = ei + 1;
                        int v = s.revTargets[ei];
                        if (s.sccVisited[v] == 0) {
                            s.sccVisited[v] = 1;
                            sp++;
                            s.dfsNode[sp] = v;
                            s.dfsEdge[sp] = s.revHead[v];
                        }
                    } else {
                        s.sccVertices[sccTotalVertices++] = u;
                        sp--;
                    }
                }
                s.sccOffsets[++sccCount] = sccTotalVertices;
            }

            if (sccCount != n) {
                for (int sccIdx = 0; sccIdx < sccCount; sccIdx++) {
                    int compStart = s.sccOffsets[sccIdx];
                    int compEnd = s.sccOffsets[sccIdx + 1];
                    int m = compEnd - compStart;
                    if (m <= 1) continue;

                    int mark = s.nextSccMark();
                    for (int k = compStart; k < compEnd; k++) {
                        int u = s.sccVertices[k];
                        s.inScc[u] = mark;
                        s.origToSub[u] = -1;
                    }

                    if (sccCount == 1) {
                        for (int su = 0; su < m; su++) {
                            s.subVertices[su] = su;
                            s.origToSub[su] = su;
                        }
                    } else {
                        if (!buildHashSetIterationOrder(compStart, m, s)) {
                            return false;
                        }
                        int subV = 0;
                        for (int i = 0; i < m; i++) {
                            int u = s.compBucketOrder[i];
                            if (s.origToSub[u] < 0) {
                                s.subVertices[subV] = u;
                                s.origToSub[u] = subV++;
                            }
                            int deg = edgeCounts[u];
                            int[] t = targets[u];
                            for (int e = 0; e < deg; e++) {
                                int v = t[e];
                                if (s.inScc[v] == mark && s.origToSub[v] < 0) {
                                    s.subVertices[subV] = v;
                                    s.origToSub[v] = subV++;
                                }
                            }
                        }
                    }

                    s.subEdgeHead[0] = 0;
                    int subEdgeCount = 0;
                    for (int su = 0; su < m; su++) {
                        int u = s.subVertices[su];
                        int deg = edgeCounts[u];
                        int base = edgeBase[u];
                        int[] t = targets[u];
                        int[] w = weights[u];
                        for (int e = 0; e < deg; e++) {
                            int v = t[e];
                            if (sccCount == 1 || s.inScc[v] == mark) {
                                s.subEdgeTarget[subEdgeCount] = s.origToSub[v];
                                s.subEdgeWeight[subEdgeCount] = w[e];
                                s.subEdgeOrigEid[subEdgeCount] = base + e;
                                subEdgeCount++;
                            }
                        }
                        s.subEdgeHead[su + 1] = subEdgeCount;
                    }

                    int numShuffles = Math.min(10, m);
                    int totalCopies = numShuffles + 1;
                    s.ensureCopyStorage(totalCopies, subEdgeCount, m);
                    if (subEdgeCount > 0) {
                        System.arraycopy(s.subEdgeTarget, 0, s.copyTargets, 0, subEdgeCount);
                    }

                    int[] mapping = s.shuffleMapping;
                    for (int i = 0; i < m; i++) {
                        mapping[i] = i;
                    }
                    Random rnd = s.shuffleRandom;
                    rnd.setSeed(7L);
                    for (int c = 1; c < totalCopies; c++) {
                        for (int i = m; i > 1; i--) {
                            int j = rnd.nextInt(i);
                            int tmp = mapping[i - 1];
                            mapping[i - 1] = mapping[j];
                            mapping[j] = tmp;
                        }
                        int copyBase = c * subEdgeCount;
                        if (subEdgeCount > 0) {
                            System.arraycopy(s.subEdgeTarget, 0, s.copyTargets, copyBase, subEdgeCount);
                        }
                        for (int su = 0; su < m; su++) {
                            int from = copyBase + s.subEdgeHead[su];
                            int to = copyBase + s.subEdgeHead[su + 1];
                            for (int p = from + 1; p < to; p++) {
                                int key = s.copyTargets[p];
                                int keyRank = mapping[key];
                                int q = p - 1;
                                while (q >= from && mapping[s.copyTargets[q]] > keyRank) {
                                    s.copyTargets[q + 1] = s.copyTargets[q];
                                    q--;
                                }
                                s.copyTargets[q + 1] = key;
                            }
                        }
                    }

                    int bestWeight = Integer.MAX_VALUE;
                    int bestSize = Integer.MAX_VALUE;
                    boolean hasBest = false;
                    int maxRounds = Math.max(1, 1_000_000 / (m + subEdgeCount));

                    for (int startU = 0; startU < m; startU++) {
                        for (int c = 0; c < totalCopies; c++) {
                            int copyBase = c * subEdgeCount;
                            Arrays.fill(s.subVisited, 0, m, (byte) 0);
                            int dfsCount = 0;
                            int sp = 0;
                            s.subVisited[startU] = 1;
                            s.dfsNode[0] = startU;
                            s.dfsEdge[0] = s.subEdgeHead[startU];
                            while (sp >= 0) {
                                int su = s.dfsNode[sp];
                                int ei = s.dfsEdge[sp];
                                int eEnd = s.subEdgeHead[su + 1];
                                if (ei < eEnd) {
                                    s.dfsEdge[sp] = ei + 1;
                                    int sw = s.copyTargets[copyBase + ei];
                                    if (s.subVisited[sw] == 0) {
                                        s.subVisited[sw] = 1;
                                        sp++;
                                        s.dfsNode[sp] = sw;
                                        s.dfsEdge[sp] = s.subEdgeHead[sw];
                                    }
                                } else {
                                    s.dfsOrder[dfsCount++] = su;
                                    sp--;
                                }
                            }
                            if (dfsCount != m) {
                                return false;
                            }

                            Arrays.fill(s.subVisited, 0, m, (byte) 0);
                            int weight = 0;
                            int size = 0;
                            for (int i = 0; i < m; i++) {
                                int su = s.dfsOrder[i];
                                s.subVisited[su] = 1;
                                int eStart = s.subEdgeHead[su];
                                int eEnd = s.subEdgeHead[su + 1];
                                for (int e = eStart; e < eEnd; e++) {
                                    int sw = s.subEdgeTarget[e];
                                    if (s.subVisited[sw] == 0) {
                                        weight += s.subEdgeWeight[e];
                                        size++;
                                    }
                                }
                                if (weight > bestWeight) {
                                    break;
                                }
                            }
                            if (weight < bestWeight || (weight == bestWeight && size < bestSize)) {
                                System.arraycopy(s.dfsOrder, 0, s.bestOrder, 0, m);
                                bestWeight = weight;
                                bestSize = size;
                                hasBest = true;
                            }
                        }
                        if (--maxRounds == 0) {
                            break;
                        }
                    }

                    if (hasBest) {
                        Arrays.fill(s.subVisited, 0, m, (byte) 0);
                        for (int i = 0; i < m; i++) {
                            int su = s.bestOrder[i];
                            s.subVisited[su] = 1;
                            int eStart = s.subEdgeHead[su];
                            int eEnd = s.subEdgeHead[su + 1];
                            for (int e = eStart; e < eEnd; e++) {
                                int sw = s.subEdgeTarget[e];
                                if (s.subVisited[sw] == 0) {
                                    s.edgeRemoved[s.subEdgeOrigEid[e]] = 1;
                                }
                            }
                        }
                    }
                }
            }

            for (int u = 0; u < n; u++) {
                int kept = 0;
                int base = edgeBase[u];
                int deg = edgeCounts[u];
                int[] t = targets[u];
                int[] w = weights[u];
                int[] kt = keptTargets[u];
                int[] kw = keptWeights[u];
                for (int e = 0; e < deg; e++) {
                    if (s.edgeRemoved[base + e] == 0) {
                        kt[kept] = t[e];
                        kw[kept] = w[e];
                        kept++;
                    }
                }
                keptEdgeCounts[u] = kept;
            }
            toposortKeptVerticesIntoCache(n, s);
            return true;
        }

        private boolean buildHashSetIterationOrder(int compStart, int m, Scratch s) {
            int cap = 16;
            int threshold = 12;
            s.ensureHashCap(cap, m);
            Arrays.fill(s.hsHead, 0, cap, -1);
            Arrays.fill(s.hsTail, 0, cap, -1);
            Arrays.fill(s.hsBinLen, 0, cap, 0);
            int hsSize = 0;
            for (int k = 0; k < m; k++) {
                int u = s.sccVertices[compStart + k];
                Object obj = vertices[u];
                int rawH = (obj == null) ? 0 : obj.hashCode();
                int hash = rawH ^ (rawH >>> 16);
                s.hsVertex[k] = u;
                s.hsHash[k] = hash;
                s.hsNext[k] = -1;
                int b = hash & (cap - 1);
                if (s.hsHead[b] < 0) {
                    s.hsHead[b] = k;
                    s.hsTail[b] = k;
                } else {
                    if (s.hsBinLen[b] >= 8) {
                        return false;
                    }
                    s.hsNext[s.hsTail[b]] = k;
                    s.hsTail[b] = k;
                }
                s.hsBinLen[b]++;
                if (++hsSize > threshold) {
                    int oldCap = cap;
                    cap <<= 1;
                    threshold <<= 1;
                    s.ensureHashCap(cap, m);
                    Arrays.fill(s.hsHead, oldCap, cap, -1);
                    Arrays.fill(s.hsTail, oldCap, cap, -1);
                    Arrays.fill(s.hsBinLen, oldCap, cap, 0);
                    for (int ob = 0; ob < oldCap; ob++) {
                        int node = s.hsHead[ob];
                        if (node < 0) continue;
                        int loHead = -1, loTail = -1, loLen = 0;
                        int hiHead = -1, hiTail = -1, hiLen = 0;
                        while (node >= 0) {
                            int nxt = s.hsNext[node];
                            s.hsNext[node] = -1;
                            if ((s.hsHash[node] & oldCap) == 0) {
                                if (loHead < 0) loHead = node; else s.hsNext[loTail] = node;
                                loTail = node;
                                loLen++;
                            } else {
                                if (hiHead < 0) hiHead = node; else s.hsNext[hiTail] = node;
                                hiTail = node;
                                hiLen++;
                            }
                            node = nxt;
                        }
                        s.hsHead[ob] = loHead; s.hsTail[ob] = loTail; s.hsBinLen[ob] = loLen;
                        s.hsHead[ob + oldCap] = hiHead; s.hsTail[ob + oldCap] = hiTail; s.hsBinLen[ob + oldCap] = hiLen;
                    }
                }
            }
            int outIdx = 0;
            for (int b = 0; b < cap; b++) {
                for (int node = s.hsHead[b]; node >= 0; node = s.hsNext[node]) {
                    s.compBucketOrder[outIdx++] = s.hsVertex[node];
                }
            }
            return true;
        }

        private <V> void computeFallback(int n, FeedbackArcSetProvider provider, Scratch scratch) {
            MapDigraph<V> reference = new MapDigraph<>();
            for (int u = 0; u < n; u++) {
                @SuppressWarnings("unchecked") V vertex = (V) vertices[u];
                reference.add(vertex);
            }
            for (int u = 0; u < n; u++) {
                @SuppressWarnings("unchecked") V src = (V) vertices[u];
                int deg = edgeCounts[u];
                int[] t = targets[u], w = weights[u];
                for (int e = 0; e < deg; e++) {
                    @SuppressWarnings("unchecked") V dst = (V) vertices[t[e]];
                    reference.put(src, dst, w[e]);
                }
            }
            FeedbackArcSet<V> fas = provider.getFeedbackArcSet(reference, reference, FeedbackArcSetPolicy.MIN_WEIGHT);
            for (V src : fas.vertices()) for (V dst : fas.targets(src)) reference.remove(src, dst);
            for (int u = 0; u < n; u++) {
                @SuppressWarnings("unchecked") V src = (V) vertices[u];
                int deg = edgeCounts[u];
                int[] t = targets[u], w = weights[u];
                int[] kt = keptTargets[u], kw = keptWeights[u];
                int kept = 0;
                for (int e = 0; e < deg; e++) {
                    int v = t[e];
                    @SuppressWarnings("unchecked") V dst = (V) vertices[v];
                    if (reference.contains(src, dst)) {
                        kt[kept] = v;
                        kw[kept] = w[e];
                        kept++;
                    }
                }
                keptEdgeCounts[u] = kept;
            }
            toposortKeptVerticesIntoCache(n, scratch);
        }

        private void toposortKeptVerticesIntoCache(int n, Scratch s) {
            s.ensureVertexCap(n);
            int[] color = s.color;
            int[] dfsNode = s.dfsNode;
            int[] dfsEdge = s.dfsEdge;
            int[] post = s.postOrder;
            Arrays.fill(color, 0, n, 0);

            int count = 0;
            for (int start = 0; start < n; start++) {
                if (color[start] != 0) continue;
                int sp = 0;
                color[start] = 1;
                dfsNode[0] = start;
                dfsEdge[0] = 0;
                while (sp >= 0) {
                    int u = dfsNode[sp];
                    int ei = dfsEdge[sp];
                    int deg = keptEdgeCounts[u];
                    int[] row = keptTargets[u];
                    if (ei < deg) {
                        dfsEdge[sp] = ei + 1;
                        int v = row[ei];
                        if (v != u && color[v] == 0) {
                            color[v] = 1;
                            sp++;
                            dfsNode[sp] = v;
                            dfsEdge[sp] = 0;
                        }
                    } else {
                        color[u] = 2;
                        post[count++] = u;
                        sp--;
                    }
                }
            }

            if (order.length < count) {
                order = Arrays.copyOf(order, count);
            }
            for (int i = count - 1, dst = 0; i >= 0; i--) {
                order[dst++] = vertices[post[i]];
            }
            if (cachedOrderCount > count) {
                Arrays.fill(order, count, cachedOrderCount, null);
            }
            cachedOrderCount = count;
        }

        <V> void apply(PooledRenderDigraph<V> graph, List<V> out) {
            graph.edgeCount = 0;
            for (int u = 0; u < cachedVertexCount; u++) {
                int count = keptEdgeCounts[u];
                if (count > 0) {
                    System.arraycopy(keptTargets[u], 0, graph.outTargets[u], 0, count);
                    System.arraycopy(keptWeights[u], 0, graph.outWeights[u], 0, count);
                }
                graph.outDegree[u] = count;
                graph.edgeCount += count;
            }
            for (int i = 0; i < cachedOrderCount; i++) {
                @SuppressWarnings("unchecked") V vertex = (V) order[i];
                out.add(vertex);
            }
        }
    }

    static final class Scratch {
        int[] color = IntArrays.EMPTY_ARRAY, dfsNode = IntArrays.EMPTY_ARRAY;
        int[] dfsEdge = IntArrays.EMPTY_ARRAY, postOrder = IntArrays.EMPTY_ARRAY;
        int[] partner = IntArrays.EMPTY_ARRAY, droppedSlot = IntArrays.EMPTY_ARRAY;
        int[] edgeBase = IntArrays.EMPTY_ARRAY;
        byte[] sccVisited = new byte[0];
        int[] finishStack = IntArrays.EMPTY_ARRAY;
        int[] revDegree = IntArrays.EMPTY_ARRAY, revHead = IntArrays.EMPTY_ARRAY, revCursor = IntArrays.EMPTY_ARRAY;
        int[] sccVertices = IntArrays.EMPTY_ARRAY, sccOffsets = IntArrays.EMPTY_ARRAY;
        int[] inScc = IntArrays.EMPTY_ARRAY, origToSub = IntArrays.EMPTY_ARRAY;
        int[] compBucketOrder = IntArrays.EMPTY_ARRAY, subVertices = IntArrays.EMPTY_ARRAY;
        int[] subEdgeHead = IntArrays.EMPTY_ARRAY;
        int[] shuffleMapping = IntArrays.EMPTY_ARRAY, dfsOrder = IntArrays.EMPTY_ARRAY, bestOrder = IntArrays.EMPTY_ARRAY;
        byte[] subVisited = new byte[0];
        byte[] edgeRemoved = new byte[0];
        int[] revTargets = IntArrays.EMPTY_ARRAY;
        int[] subEdgeTarget = IntArrays.EMPTY_ARRAY, subEdgeWeight = IntArrays.EMPTY_ARRAY, subEdgeOrigEid = IntArrays.EMPTY_ARRAY;
        int[] copyTargets = IntArrays.EMPTY_ARRAY;
        int[] hsHead = IntArrays.EMPTY_ARRAY, hsTail = IntArrays.EMPTY_ARRAY, hsBinLen = IntArrays.EMPTY_ARRAY;
        int[] hsNext = IntArrays.EMPTY_ARRAY, hsVertex = IntArrays.EMPTY_ARRAY, hsHash = IntArrays.EMPTY_ARRAY;
        int sccMark;
        final Random shuffleRandom = new Random(7L);
        final FeedbackArcSetProvider provider = new SimpleFeedbackArcSetProvider();

        int nextSccMark() {
            int next = ++sccMark;
            if (next == 0) {
                Arrays.fill(inScc, 0);
                next = sccMark = 1;
            }
            return next;
        }

        void ensureVertexCap(int n) {
            if (color.length < n) {
                int cap = Math.max(32, Math.max(n, color.length * 2));
                color = new int[cap]; dfsNode = new int[cap]; dfsEdge = new int[cap]; postOrder = new int[cap];
                partner = new int[cap]; droppedSlot = new int[cap];
                edgeBase = new int[cap + 1];
                sccVisited = new byte[cap];
                finishStack = new int[cap];
                revDegree = new int[cap]; revHead = new int[cap + 1]; revCursor = new int[cap];
                sccVertices = new int[cap]; sccOffsets = new int[cap + 1];
                inScc = new int[cap]; origToSub = new int[cap];
                compBucketOrder = new int[cap]; subVertices = new int[cap];
                subEdgeHead = new int[cap + 1];
                shuffleMapping = new int[cap]; dfsOrder = new int[cap]; bestOrder = new int[cap];
                subVisited = new byte[cap];
            } else if (edgeBase.length < n + 1) {
                int cap = color.length;
                edgeBase = new int[cap + 1];
                revHead = new int[cap + 1];
                sccOffsets = new int[cap + 1];
                subEdgeHead = new int[cap + 1];
            }
        }

        void ensureEdgeCap(int e) {
            if (edgeRemoved.length >= e) return;
            int cap = Math.max(64, Math.max(e, edgeRemoved.length * 2));
            edgeRemoved = new byte[cap];
            revTargets = new int[cap];
            subEdgeTarget = new int[cap];
            subEdgeWeight = new int[cap];
            subEdgeOrigEid = new int[cap];
        }

        void ensureCopyStorage(int totalCopies, int subEdgeCount, int m) {
            int needed = totalCopies * subEdgeCount;
            if (copyTargets.length < needed) {
                copyTargets = new int[Math.max(needed, Math.max(128, copyTargets.length * 2))];
            }
            ensureVertexCap(m);
        }

        void ensureHashCap(int bucketCap, int nodeCount) {
            if (hsHead.length < bucketCap) {
                int cap = Math.max(bucketCap, hsHead.length * 2);
                hsHead = Arrays.copyOf(hsHead, cap);
                hsTail = Arrays.copyOf(hsTail, cap);
                hsBinLen = Arrays.copyOf(hsBinLen, cap);
            }
            if (hsNext.length < nodeCount) {
                int cap = Math.max(32, Math.max(nodeCount, hsNext.length * 2));
                hsNext = new int[cap];
                hsVertex = new int[cap];
                hsHash = new int[cap];
            }
        }
    }
}
