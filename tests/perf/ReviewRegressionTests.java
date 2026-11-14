package dev.sunlit.bettersunlit.tests.perf;

import com.hrznstudio.emojiful.Constants;
import com.hrznstudio.emojiful.api.Emoji;
import dev.sunlit.bettersunlit.client.EmojiRenderPerf;
import dev.sunlit.bettersunlit.client.GraphOrderPerf;
import dev.sunlit.bettersunlit.client.IrisAstCachePerf;
import dev.sunlit.bettersunlit.perf.LogBegonePerf;
import de.odysseus.ithaka.digraph.*;
import de.odysseus.ithaka.digraph.util.fas.*;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.transform.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Regression fixtures from the independent review; no game or GL context. */
final class ReviewRegressionTests {
    static void run() throws Exception {
        graphs(); astOwner(); emojiMutation(); logMutation();
        System.out.println("PASS: review regressions (native graph order, cold/growing scratch, actual AST owner, live emoji/log updates)");
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void equal(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": " + expected + " != " + actual);
    }
    static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); return f;
    }

    private static void graphs() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread fresh = new Thread(() -> {
            try {
                for (int n : new int[]{2, 33, 129, 257}) {
                    var g = new GraphOrderPerf.PooledRenderDigraph<String>();
                    for (int i = 0; i < n - 1; i++) g.put("n" + i, "n" + (i + 1), 1);
                    check(g.isAcyclic(), "First/growing scratch DAG");
                    g.put("n" + (n - 1), "n0", 1);
                    check(!g.isAcyclic(), "First/growing scratch cycle");
                }
            } catch (Throwable t) { failure.set(t); }
        });
        fresh.start(); fresh.join();
        if (failure.get() != null) throw new AssertionError("Fresh-thread graph", failure.get());

        for (int n : new int[]{12, 17, 25}) {
            String[] vertices = new String[n]; for (int i = 0; i < n; i++) vertices[i] = "dense" + i;
            int[][] edges = new int[n][n];
            for (int u = 0; u < n; u++) for (int v = 0; v < n; v++) if (u != v) edges[u][v] = 1 + (u + v) % 7;
            var graph = new GraphOrderPerf.PooledRenderDigraph<String>();
            assertNative(graph, vertices, edges);
            // Same exact input exercises the cached native result, then a weight change must miss.
            assertNative(graph, vertices, edges);
            edges[0][1] += 10; assertNative(graph, vertices, edges);
        }
        Random rng = new Random(20261114L);
        for (int trial = 0; trial < 120; trial++) {
            int n = 3 + rng.nextInt(14); String[] vertices = new String[n];
            for (int i = 0; i < n; i++) vertices[i] = "v" + i;
            int[][] edges = new int[n][n];
            for (int i = 0; i < n * 3; i++) { int u = rng.nextInt(n), v = rng.nextInt(n); if (u != v) edges[u][v] = 1 + rng.nextInt(20); }
            assertNative(new GraphOrderPerf.PooledRenderDigraph<>(), vertices, edges);
        }
        var mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        String[] vertices = {"a", "b", "c"}; int[][] edges = {{0,5,1},{1,0,7},{4,2,0}};
        var graph = new GraphOrderPerf.PooledRenderDigraph<String>(); var out = new ArrayList<String>(3);
        for (int i = 0; i < 2000; i++) { fill(graph, vertices, edges); out.clear(); GraphOrderPerf.orderPooledGraph(graph, out); }
        long id = Thread.currentThread().getId(), before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 2000; i++) { fill(graph, vertices, edges); out.clear(); GraphOrderPerf.orderPooledGraph(graph, out); }
        equal(0L, mx.getThreadAllocatedBytes(id) - before, "Repeated cyclic graph cache allocations");
    }
    private static void fill(GraphOrderPerf.PooledRenderDigraph<String> graph, String[] vertices, int[][] edges) {
        graph.clear(); for (String v : vertices) graph.add(v);
        for (int u = 0; u < vertices.length; u++) for (int v = 0; v < vertices.length; v++)
            if (edges[u][v] != 0) graph.put(vertices[u], vertices[v], edges[u][v]);
    }
    private static void assertNative(GraphOrderPerf.PooledRenderDigraph<String> graph, String[] vertices, int[][] edges) {
        fill(graph, vertices, edges); var original = new MapDigraph<String>();
        for (String v : vertices) original.add(v);
        for (int u = 0; u < vertices.length; u++) for (int v = 0; v < vertices.length; v++)
            if (edges[u][v] != 0) original.put(vertices[u], vertices[v], edges[u][v]);
        var provider = new SimpleFeedbackArcSetProvider();
        var fas = provider.getFeedbackArcSet(original, original, FeedbackArcSetPolicy.MIN_WEIGHT);
        for (String u : fas.vertices()) for (String v : fas.targets(u)) original.remove(u, v);
        List<String> expected = Digraphs.toposort(original, false), actual = new ArrayList<>();
        GraphOrderPerf.orderPooledGraph(graph, actual);
        equal(expected, actual, "Native ordering, including equal-weight tie breaks");
        for (String u : vertices) for (String v : vertices) equal(original.get(u,v), graph.get(u,v), "Native kept edge " + u + " -> " + v);
    }

    private static void astOwner() throws Exception {
        ASTParser parser = new EnumASTTransformer<>(Thread.State.class);
        ASTParser singleton = ASTParser._getInternalInstance();
        Field build = field(ASTParser.class, "buildCache"), impl = field(ASTParser.class, "parser");
        Field parse = field(impl.get(parser).getClass(), "parseCache");
        Object singletonCache = build.get(singleton);
        for (int i = 0; i < 64; i++) parser.parseExpression(RootSupplier.DEFAULT, "x + " + i + ".0");
        check(((Map<?,?>)build.get(parser)).size() > 16, "Fill actual transformer build cache");
        check(((Map<?,?>)parse.get(impl.get(parser))).size() > 16, "Fill actual transformer parse cache");
        String expression = "a + b * 2.0";
        String expected = ASTPrinter.printSimple(parser.parseExpression(RootSupplier.DEFAULT, expression));
        IrisAstCachePerf.registerTransformer(parser);
        try {
            IrisAstCachePerf.trimAstCaches();
            equal(0, ((Map<?,?>)build.get(parser)).size(), "Actual owner build cache cleared");
            equal(0, ((Map<?,?>)parse.get(impl.get(parser))).size(), "Actual owner parse cache cleared");
            check(singletonCache == build.get(singleton), "Unrelated singleton left intact");
            equal(expected, ASTPrinter.printSimple(parser.parseExpression(RootSupplier.DEFAULT, expression)), "Reparse output after trim");
            for (int i = 0; i < 64; i++) parser.parseExpression(RootSupplier.DEFAULT, "y + " + i + ".0");
            check(((Map<?,?>)build.get(parser)).size() <= 16, "Actual owner build capacity");
            check(((Map<?,?>)parse.get(impl.get(parser))).size() <= 16, "Actual owner parse capacity");
        } finally { IrisAstCachePerf.registerTransformer(null); }
    }

    private static Emoji emoji(String trigger) { Emoji e = new Emoji(); e.strings = new ArrayList<>(List.of(trigger)); return e; }
    private static void emojiMutation() throws Exception {
        List<Emoji> saved = new ArrayList<>(Constants.EMOJI_LIST);
        try {
            Constants.EMOJI_LIST.clear();
            Emoji first = emoji(":aa:"), middle = emoji("~~"), last = emoji(":zz:");
            Constants.EMOJI_LIST.addAll(List.of(first, middle, last)); EmojiRenderPerf.invalidateIndex();
            check(EmojiRenderPerf.mightContainEmoji("~~"), "Build initial emoji index");
            var cache = EmojiRenderPerf.createBoundedCache();
            cache.put("~~", org.apache.commons.lang3.tuple.Pair.of("cached", new HashMap<>()));
            Constants.EMOJI_LIST.set(1, emoji("!!"));
            check(EmojiRenderPerf.mightContainEmoji("!!"), "Same-size middle replacement");
            equal(0L, cache.size(), "Recent strings invalidated on emoji data change");
            middle = Constants.EMOJI_LIST.get(1);
            field(Emoji.class, "regex").set(middle, "!!");
            field(Emoji.class, "regexPattern").set(middle, Pattern.compile("!!"));
            middle.strings.set(0, "@@");
            check(EmojiRenderPerf.mightContainEmoji("@@"), "Same-size trigger-list edit");
            equal(null, field(Emoji.class, "regexPattern").get(middle), "Native stale pattern cleared");
            middle.strings = List.of("##");
            check(EmojiRenderPerf.mightContainEmoji("##"), "Replacement of public strings field");
            check(!EmojiRenderPerf.mightContainEmoji("ordinary text"), "Plain text still rejected");
        } finally { Constants.EMOJI_LIST.clear(); Constants.EMOJI_LIST.addAll(saved); EmojiRenderPerf.invalidateIndex(); }
    }

    private static void logMutation() throws Exception {
        var phrases = new ArrayList<String>(List.of("old")); var regexes = new ArrayList<String>();
        LogBegonePerf.invalidate(); check(LogBegonePerf.shouldFilter("old", phrases, regexes), "Initial phrase");
        phrases.set(0, "new"); check(LogBegonePerf.shouldFilter("new", phrases, regexes), "Same-size phrase edit");
        check(!LogBegonePerf.shouldFilter("old", phrases, regexes), "Old phrase removed");
        phrases.clear(); regexes.add("^old$"); check(LogBegonePerf.shouldFilter("old", phrases, regexes), "Initial regex");
        regexes.set(0, "^new$"); check(LogBegonePerf.shouldFilter("new", phrases, regexes), "Same-size regex edit");
        check(!LogBegonePerf.shouldFilter("old", phrases, regexes), "Old regex removed");
        ThreadLocal<?> local = (ThreadLocal<?>)field(LogBegonePerf.class, "MATCHERS").get(null);
        Object holder = local.get(); Matcher[] matchers = (Matcher[])field(holder.getClass(), "matchers").get(holder);
        for (Matcher matcher : matchers) if (matcher != null) equal(0, matcher.regionEnd(), "Matcher releases last message");
        LogBegonePerf.invalidate();
    }
}
