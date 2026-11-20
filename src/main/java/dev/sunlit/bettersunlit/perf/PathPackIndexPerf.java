package dev.sunlit.bettersunlit.perf;

import net.minecraftforge.resource.PathPackResources;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Lazy, allocation-free path-prefix negative filter and root-file existence cache for immutable
 * {@link PathPackResources} instances backed by mod JAR filesystems ({@code ResourcePackLoader$1}).
 *
 * <p>Prevents repeated {@code String.split("/")}, {@code ModFile.findResource}, {@code UnionPath},
 * and {@code Files.exists(..., new LinkOption[0])} allocations when probing missing resources across
 * hundreds of mod packs that share common namespaces (such as {@code minecraft} or {@code forge}).
 */
public final class PathPackIndexPerf {
    private static final Object ROOT_MISS = new Object();
    private static final Set<String> EMPTY_CHILDREN = Set.of();

    private PathPackIndexPerf() {
    }

    public static final class PackIndex {
        private final ConcurrentHashMap<String, Object> rootFiles = new ConcurrentHashMap<>(4);
        private final ConcurrentHashMap<String, Set<String>> dirChildren = new ConcurrentHashMap<>(8);

        public Object getRootCached(String fileName, Function<String[], Path> resolver) {
            Object cached = rootFiles.get(fileName);
            if (cached != null) {
                return cached;
            }
            Path resolved = resolver.apply(new String[]{fileName});
            Object value = (resolved != null && Files.exists(resolved)) ? resolved : ROOT_MISS;
            Object prev = rootFiles.putIfAbsent(fileName, value);
            return prev != null ? prev : value;
        }

        public boolean isRootMiss(Object cached) {
            return cached == ROOT_MISS;
        }

        public boolean mayContainAssetOrDataPath(
                String typeDir,
                String namespace,
                String relPath,
                Function<String[], Path> resolver) {
            if (relPath == null || relPath.isEmpty()) {
                return true;
            }
            int slash1 = relPath.indexOf('/');
            String seg1 = slash1 >= 0 ? relPath.substring(0, slash1) : relPath;
            if (seg1.isEmpty() || ".".equals(seg1) || "..".equals(seg1)) {
                return true;
            }
            Set<String> level1 = getLevelChildren(resolver, typeDir, namespace, null);
            if (!level1.contains(seg1)) {
                return false;
            }
            if (slash1 < 0 || slash1 + 1 >= relPath.length()) {
                return true;
            }
            int slash2 = relPath.indexOf('/', slash1 + 1);
            String seg2 = slash2 >= 0 ? relPath.substring(slash1 + 1, slash2) : relPath.substring(slash1 + 1);
            if (seg2.isEmpty() || ".".equals(seg2) || "..".equals(seg2)) {
                return true;
            }
            Set<String> level2 = getLevelChildren(resolver, typeDir, namespace, seg1);
            return level2.contains(seg2);
        }

        public boolean mayContainSegments(
                String typeDir,
                String namespace,
                String seg1,
                String seg2,
                Function<String[], Path> resolver) {
            if (seg1 == null || seg1.isEmpty() || seg1.indexOf('/') >= 0 || ".".equals(seg1) || "..".equals(seg1)) {
                return true;
            }
            Set<String> level1 = getLevelChildren(resolver, typeDir, namespace, null);
            if (!level1.contains(seg1)) {
                return false;
            }
            if (seg2 == null || seg2.isEmpty() || seg2.indexOf('/') >= 0 || ".".equals(seg2) || "..".equals(seg2)) {
                return true;
            }
            Set<String> level2 = getLevelChildren(resolver, typeDir, namespace, seg1);
            return level2.contains(seg2);
        }

        private Set<String> getLevelChildren(
                Function<String[], Path> resolver,
                String typeDir,
                String namespace,
                String seg1) {
            String key = seg1 == null
                    ? (typeDir + '/' + namespace)
                    : (typeDir + '/' + namespace + '/' + seg1);
            Set<String> cached = dirChildren.get(key);
            if (cached != null) {
                return cached;
            }
            Path dir = seg1 == null
                    ? resolver.apply(new String[]{typeDir, namespace})
                    : resolver.apply(new String[]{typeDir, namespace, seg1});
            Set<String> listed = listChildNames(dir);
            Set<String> prev = dirChildren.putIfAbsent(key, listed);
            return prev != null ? prev : listed;
        }

        private static Set<String> listChildNames(Path dir) {
            if (dir == null || !Files.isDirectory(dir)) {
                return EMPTY_CHILDREN;
            }
            try (Stream<Path> stream = Files.list(dir)) {
                String[] names = stream
                        .map(PathPackIndexPerf::childName)
                        .filter(s -> s != null && !s.isEmpty())
                        .toArray(String[]::new);
                if (names.length == 0) {
                    return EMPTY_CHILDREN;
                }
                return Set.of(names);
            } catch (Throwable ignored) {
                return EMPTY_CHILDREN;
            }
        }
    }

    public static boolean isImmutableModPack(PathPackResources pack) {
        if (pack == null) {
            return false;
        }
        String clsName = pack.getClass().getName();
        if (!"net.minecraftforge.resource.ResourcePackLoader$1".equals(clsName)) {
            return false;
        }
        Path source = pack.getSource();
        return source != null && source.getFileSystem() != FileSystems.getDefault();
    }

    private static String childName(Path p) {
        if (p == null) {
            return null;
        }
        Path fn = p.getFileName();
        if (fn == null) {
            return null;
        }
        String s = fn.toString();
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
