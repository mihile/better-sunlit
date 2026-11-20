package dev.sunlit.bettersunlit.client;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Background pre-scanner and fast zero-regex parser for QuickPack's file-tree indexing.
 */
public final class QuickPackPerf {
    public static final class PrebuiltTree {
        public final TreeSet<String> fileTree;
        public final Map<String, Set<String>> namespaces;
        final long length;
        final long mtime;

        PrebuiltTree(TreeSet<String> fileTree, Map<String, Set<String>> namespaces, long length, long mtime) {
            this.fileTree = fileTree;
            this.namespaces = namespaces;
            this.length = length;
            this.mtime = mtime;
        }
    }

    private static final AtomicBoolean PRESCAN_STARTED = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, CompletableFuture<PrebuiltTree>> PENDING = new ConcurrentHashMap<>(32);
    private static final ConcurrentHashMap<String, SoftReference<PrebuiltTree>> CACHE = new ConcurrentHashMap<>(32);

    private QuickPackPerf() {
    }

    public static void schedulePrescan() {
        if (!PRESCAN_STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            Path rpDir = FMLPaths.GAMEDIR.get().resolve("resourcepacks");
            if (!Files.isDirectory(rpDir)) {
                return;
            }
            File[] files = rpDir.toFile().listFiles((d, name) -> name.endsWith(".zip"));
            if (files == null || files.length == 0) {
                return;
            }
            List<File> targets = new ArrayList<>(files.length);
            for (File f : files) {
                if (f.isFile()) {
                    targets.add(f);
                    PENDING.putIfAbsent(normalizeKey(f), new CompletableFuture<>());
                }
            }
            if (targets.isEmpty()) {
                return;
            }
            int workers = Math.min(4, targets.size());
            AtomicInteger cursor = new AtomicInteger(0);
            for (int w = 0; w < workers; w++) {
                Thread t = new Thread(() -> {
                    while (true) {
                        int idx = cursor.getAndIncrement();
                        if (idx >= targets.size()) {
                            return;
                        }
                        File f = targets.get(idx);
                        String key = normalizeKey(f);
                        CompletableFuture<PrebuiltTree> fut = PENDING.get(key);
                        PrebuiltTree built = null;
                        try (ZipFile zf = new ZipFile(f)) {
                            built = buildSingleRootTree(zf, f.length(), f.lastModified());
                            CACHE.put(key, new SoftReference<>(built));
                        } catch (Throwable ignored) {
                        } finally {
                            if (fut != null) {
                                fut.complete(built);
                            }
                        }
                    }
                }, "BetterSunlit-QuickPack-" + w);
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                t.start();
            }
        } catch (Throwable ignored) {
        }
    }

    public static PrebuiltTree getOrAwaitPrebuilt(ZipFile zip) {
        if (zip == null) {
            return null;
        }
        File f = new File(zip.getName());
        String key = normalizeKey(f);
        long len = f.length();
        long mt = f.lastModified();

        SoftReference<PrebuiltTree> ref = CACHE.get(key);
        PrebuiltTree hit = ref != null ? ref.get() : null;
        if (hit != null && hit.length == len && hit.mtime == mt) {
            return hit;
        }
        CompletableFuture<PrebuiltTree> pending = PENDING.remove(key);
        if (pending != null) {
            try {
                PrebuiltTree awaited = pending.join();
                if (awaited != null && awaited.length == len && awaited.mtime == mt) {
                    CACHE.put(key, new SoftReference<>(awaited));
                    return awaited;
                }
            } catch (Throwable ignored) {
            }
        }
        PrebuiltTree built = buildSingleRootTree(zip, len, mt);
        CACHE.put(key, new SoftReference<>(built));
        return built;
    }

    public static PrebuiltTree buildSingleRootTree(ZipFile zip, long length, long mtime) {
        Map<String, Set<String>> namespaces = new HashMap<>(4);
        ArrayList<String> paths = new ArrayList<>(512);
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry entry = en.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            int len = name.length();
            if (len == 0) {
                continue;
            }
            int s0 = name.indexOf('/');
            if (s0 < 0) {
                continue;
            }
            String type;
            String namespace;
            if (s0 == 0) {
                // Match String.split("/") behavior when leading '/' gives empty root token in rootMap.
                int s1 = name.indexOf('/', 1);
                if (s1 < 0) {
                    continue;
                }
                type = name.substring(1, s1);
                int s2 = name.indexOf('/', s1 + 1);
                namespace = s2 >= 0 ? name.substring(s1 + 1, s2) : name.substring(s1 + 1);
                if (s2 < 0 && namespace.isEmpty()) {
                    continue;
                }
            } else {
                int s1 = name.indexOf('/', s0 + 1);
                type = name.substring(0, s0);
                namespace = s1 >= 0 ? name.substring(s0 + 1, s1) : name.substring(s0 + 1);
                if (s1 < 0 && namespace.isEmpty()) {
                    continue;
                }
            }
            if (isLowercaseRoot(namespace)) {
                Set<String> set = namespaces.get(type);
                if (set == null) {
                    set = new HashSet<>(8);
                    namespaces.put(type, set);
                }
                set.add(namespace);
            }
            paths.add(name);
        }
        Collections.sort(paths);
        TreeSet<String> fileTree = new TreeSet<>();
        fileTree.addAll(paths);
        return new PrebuiltTree(fileTree, namespaces, length, mtime);
    }

    private static boolean isLowercaseRoot(String s) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || c > 127) {
                return s.equals(s.toLowerCase(Locale.ROOT));
            }
        }
        return true;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, String> CANONICAL_PATH_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>(512);

    private static String normalizeKey(File f) {
        String raw = f.getPath();
        String cached = CANONICAL_PATH_CACHE.get(raw);
        if (cached != null) {
            return cached;
        }
        String norm;
        try {
            norm = f.getCanonicalPath();
        } catch (Throwable ignored) {
            norm = f.getAbsolutePath();
        }
        CANONICAL_PATH_CACHE.putIfAbsent(raw, norm);
        return norm;
    }
}
