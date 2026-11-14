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
        // result only when every vertex, edge, weight and provider still matches exactly.
        OrderCache cache = graph.orderCache;
        if (!cache.matches(graph, provider)) cache.compute(graph, provider, scratch);
        cache.apply(graph, out);
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
        int pairU = -1, pairV = -1, slotUV = -1, slotVU = -1;
        for (int u = 0; u < n; u++) {
            int deg = g.outDegree[u];
            int[] targets = g.outTargets[u];
            for (int e = 0; e < deg; e++) {
                int v = targets[e];
                if (v <= u) continue;
                int backSlot = findEdgeSlot(g, v, u);
                if (backSlot < 0) continue;
                if (pairU >= 0) return false;
                pairU = u;
                pairV = v;
                slotUV = e;
                slotVU = backSlot;
            }
        }
        if (pairU < 0) return false;
        int wUV = g.outWeights[pairU][slotUV];
        int wVU = g.outWeights[pairV][slotVU];
        if (wUV == wVU) return false;
        int lightFrom, lightTo, lightSlot;
        if (wUV < wVU) {
            lightFrom = pairU;
            lightTo = pairV;
            lightSlot = slotUV;
        } else {
            lightFrom = pairV;
            lightTo = pairU;
            lightSlot = slotVU;
        }
        if (hasNonSelfCycleSkippingEdge(g, lightFrom, lightSlot, s)
                || reachesViaOtherPath(g, lightTo, lightFrom, s)) {
            return false;
        }
        g.removeEdgeSlot(lightFrom, lightSlot);
        appendToposort(g, out, s);
        return true;
    }

    private static <V> int findEdgeSlot(PooledRenderDigraph<V> g, int uIdx, int vIdx) {
        int deg = g.outDegree[uIdx];
        int[] targets = g.outTargets[uIdx];
        for (int i = 0; i < deg; i++) {
            if (targets[i] == vIdx) return i;
        }
        return -1;
    }

    private static <V> boolean hasNonSelfCycleSkippingEdge(PooledRenderDigraph<V> g,
                                                           int skipU, int skipSlot, Scratch s) {
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
                    if (u == skipU && ei == skipSlot) continue;
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

    private static <V> boolean reachesViaOtherPath(PooledRenderDigraph<V> g,
                                                   int start, int target, Scratch s) {
        int n = g.vertexCount;
        int[] color = s.color;
        int[] stack = s.dfsNode;
        Arrays.fill(color, 0, n, 0);
        color[start] = 1;
        int sp = 0;
        int startDeg = g.outDegree[start];
        int[] startTargets = g.outTargets[start];
        for (int i = 0; i < startDeg; i++) {
            int w = startTargets[i];
            if (w == start || w == target || color[w] != 0) continue;
            color[w] = 1;
            stack[sp++] = w;
        }
        while (sp > 0) {
            int curr = stack[--sp];
            int deg = g.outDegree[curr];
            int[] targets = g.outTargets[curr];
            for (int i = 0; i < deg; i++) {
                int next = targets[i];
                if (next == target) return true;
                if (next != curr && color[next] == 0) {
                    color[next] = 1;
                    stack[sp++] = next;
                }
            }
        }
        return false;
    }

    private static <V> boolean hasMutualTwoNodeCycle(PooledRenderDigraph<V> g) {
        return g.hasEdgeIndex(0, 1) && g.hasEdgeIndex(1, 0);
    }

    private static <V> boolean hasNonSelfCycle(PooledRenderDigraph<V> g, Scratch s) {
        return hasNonSelfCycleSkippingEdge(g, -1, -1, s);
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
        private final OrderCache orderCache = new OrderCache();

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
        private Object[] vertices = new Object[0];
        private int[][] targets = new int[0][];
        private int[][] weights = new int[0][];
        private int[][] keptTargets = new int[0][];
        private int[][] keptWeights = new int[0][];
        private Object[] order = new Object[0];
        private FeedbackArcSetProvider owner;
        private boolean valid;

        boolean matches(PooledRenderDigraph<?> graph, FeedbackArcSetProvider provider) {
            if (!valid || owner != provider || vertices.length != graph.vertexCount) return false;
            for (int u = 0; u < vertices.length; u++) {
                if (vertices[u] != graph.vertices[u] || targets[u].length != graph.outDegree[u]) return false;
                for (int e = 0; e < targets[u].length; e++) {
                    if (targets[u][e] != graph.outTargets[u][e] || weights[u][e] != graph.outWeights[u][e]) return false;
                }
            }
            return true;
        }

        <V> void compute(PooledRenderDigraph<V> graph, FeedbackArcSetProvider provider, Scratch scratch) {
            valid = false;
            int n = graph.vertexCount;
            Object[] nextVertices = Arrays.copyOf(graph.vertices, n);
            int[][] nextTargets = new int[n][], nextWeights = new int[n][];
            MapDigraph<V> reference = new MapDigraph<>();
            for (int u = 0; u < n; u++) {
                @SuppressWarnings("unchecked") V vertex = (V) nextVertices[u];
                reference.add(vertex);
                nextTargets[u] = graph.outDegree[u] == 0 ? IntArrays.EMPTY_ARRAY : Arrays.copyOf(graph.outTargets[u], graph.outDegree[u]);
                nextWeights[u] = graph.outDegree[u] == 0 ? IntArrays.EMPTY_ARRAY : Arrays.copyOf(graph.outWeights[u], graph.outDegree[u]);
            }
            for (int u = 0; u < n; u++) for (int e = 0; e < nextTargets[u].length; e++) {
                @SuppressWarnings("unchecked") V src = (V) nextVertices[u];
                @SuppressWarnings("unchecked") V dst = (V) nextVertices[nextTargets[u][e]];
                reference.put(src, dst, nextWeights[u][e]);
            }
            FeedbackArcSet<V> fas = provider.getFeedbackArcSet(reference, reference, FeedbackArcSetPolicy.MIN_WEIGHT);
            for (V src : fas.vertices()) for (V dst : fas.targets(src)) reference.remove(src, dst);
            int[][] nextKeptTargets = new int[n][], nextKeptWeights = new int[n][];
            for (int u = 0; u < n; u++) {
                @SuppressWarnings("unchecked") V src = (V) nextVertices[u];
                int kept = reference.getOutDegree(src), i = 0;
                nextKeptTargets[u] = kept == 0 ? IntArrays.EMPTY_ARRAY : new int[kept];
                nextKeptWeights[u] = kept == 0 ? IntArrays.EMPTY_ARRAY : new int[kept];
                for (int e = 0; e < nextTargets[u].length; e++) {
                    int v = nextTargets[u][e];
                    @SuppressWarnings("unchecked") V dst = (V) nextVertices[v];
                    if (reference.contains(src, dst)) {
                        nextKeptTargets[u][i] = v; nextKeptWeights[u][i++] = nextWeights[u][e];
                    }
                }
            }
            Object[] nextOrder = toposortKeptVertices(nextVertices, nextKeptTargets, scratch);
            vertices = nextVertices; targets = nextTargets; weights = nextWeights;
            keptTargets = nextKeptTargets; keptWeights = nextKeptWeights;
            order = nextOrder; owner = provider; valid = true;
        }

        private static Object[] toposortKeptVertices(Object[] vertices, int[][] keptTargets, Scratch s) {
            int n = vertices.length;
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
                    int[] targets = keptTargets[u];
                    if (ei < targets.length) {
                        dfsEdge[sp] = ei + 1;
                        int v = targets[ei];
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

            Object[] result = new Object[count];
            for (int i = count - 1, dst = 0; i >= 0; i--) {
                result[dst++] = vertices[post[i]];
            }
            return result;
        }

        <V> void apply(PooledRenderDigraph<V> graph, List<V> out) {
            graph.edgeCount = 0;
            for (int u = 0; u < vertices.length; u++) {
                int count = keptTargets[u].length;
                if (count > 0) {
                    System.arraycopy(keptTargets[u], 0, graph.outTargets[u], 0, count);
                    System.arraycopy(keptWeights[u], 0, graph.outWeights[u], 0, count);
                }
                graph.outDegree[u] = count; graph.edgeCount += count;
            }
            for (Object v : order) { @SuppressWarnings("unchecked") V vertex = (V) v; out.add(vertex); }
        }
    }

    static final class Scratch {
        int[] color = IntArrays.EMPTY_ARRAY, dfsNode = IntArrays.EMPTY_ARRAY;
        int[] dfsEdge = IntArrays.EMPTY_ARRAY, postOrder = IntArrays.EMPTY_ARRAY;
        final FeedbackArcSetProvider provider = new SimpleFeedbackArcSetProvider();
        void ensureVertexCap(int n) {
            if (color.length >= n) return;
            int cap = Math.max(32, Math.max(n, color.length * 2));
            color = new int[cap]; dfsNode = new int[cap]; dfsEdge = new int[cap]; postOrder = new int[cap];
        }
    }
}
