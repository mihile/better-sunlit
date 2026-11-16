package dev.sunlit.bettersunlit.client;

import dev.latvian.mods.kubejs.DevProperties;
import dev.latvian.mods.kubejs.KubeJS;
import dev.latvian.mods.kubejs.KubeJSPaths;
import dev.latvian.mods.kubejs.script.data.GeneratedData;
import dev.latvian.mods.kubejs.script.data.GeneratedResourcePack;
import dev.latvian.mods.kubejs.util.ConsoleJS;
import dev.latvian.mods.kubejs.util.Lazy;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;

/**
 * Lock-free identity-cached index for KubeJS {@code GeneratedResourcePack} lookups and single-pass
 * {@code walkFileTree} validation replacing nested stream/stat scans.
 */
public final class KubeJsPackPerf {
    private static final Object INDEX_LOCK = new Object();
    private static volatile PackEntry[] INDEX_ENTRIES = new PackEntry[0];
    private static final Object PREFETCH_LOCK = new Object();
    private static final IdentityHashMap<GeneratedResourcePack, CompletableFuture<Void>> PREFETCH_FUTURES =
            new IdentityHashMap<>();
    private static final ThreadLocal<Boolean> IN_PREFETCH = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static volatile CompletableFuture<Map<ResourceLocation, GeneratedData>> EARLY_CLIENT_PREFETCH;

    private KubeJsPackPerf() {
    }

    public static void prefetchGeneratedAsync(GeneratedResourcePack pack) {
        if (pack == null) {
            return;
        }
        synchronized (PREFETCH_LOCK) {
            if (PREFETCH_FUTURES.containsKey(pack)) {
                return;
            }
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                IN_PREFETCH.set(Boolean.TRUE);
                try {
                    synchronized (pack) {
                        pack.getGenerated();
                    }
                } finally {
                    IN_PREFETCH.remove();
                    synchronized (PREFETCH_LOCK) {
                        PREFETCH_FUTURES.remove(pack);
                    }
                }
            }, ForkJoinPool.commonPool());
            PREFETCH_FUTURES.put(pack, future);
        }
    }

    public static void awaitPrefetch(GeneratedResourcePack pack) {
        if (pack == null || Boolean.TRUE.equals(IN_PREFETCH.get())) {
            return;
        }
        CompletableFuture<Void> future;
        synchronized (PREFETCH_LOCK) {
            future = PREFETCH_FUTURES.get(pack);
        }
        if (future != null) {
            future.join();
        }
    }

    public static void scheduleEarlyClientPrefetch() {
        if (EARLY_CLIENT_PREFETCH != null) {
            return;
        }
        synchronized (PREFETCH_LOCK) {
            if (EARLY_CLIENT_PREFETCH != null) {
                return;
            }
            EARLY_CLIENT_PREFETCH = CompletableFuture.supplyAsync(() -> {
                Path root = KubeJSPaths.get(PackType.CLIENT_RESOURCES);
                Map<ResourceLocation, GeneratedData> preloaded = new HashMap<>();
                populateGeneratedFromRoot(
                        root,
                        PackType.CLIENT_RESOURCES.m_10305_(),
                        preloaded,
                        false,
                        rel -> true,
                        data -> data.id().m_135815_().startsWith("lang/"));
                return preloaded;
            }, ForkJoinPool.commonPool());
        }
    }

    public static void populateGeneratedFromDisk(
            PackType packType,
            Map<ResourceLocation, GeneratedData> generated,
            Predicate<String> forgetFile,
            Predicate<GeneratedData> skipFile) {
        if (packType == null || generated == null) {
            return;
        }
        DevProperties props = DevProperties.get();
        boolean debug = props.logGeneratedData || props.debugInfo;
        if (packType == PackType.CLIENT_RESOURCES && !debug) {
            CompletableFuture<Map<ResourceLocation, GeneratedData>> earlyFuture;
            synchronized (PREFETCH_LOCK) {
                earlyFuture = EARLY_CLIENT_PREFETCH;
                EARLY_CLIENT_PREFETCH = null;
            }
            if (earlyFuture != null) {
                try {
                    Map<ResourceLocation, GeneratedData> preloaded = earlyFuture.join();
                    if (preloaded != null) {
                        for (Map.Entry<ResourceLocation, GeneratedData> entry : preloaded.entrySet()) {
                            GeneratedData data = entry.getValue();
                            if (!skipFile.test(data)) {
                                generated.put(entry.getKey(), data);
                            }
                        }
                        return;
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        Path root = KubeJSPaths.get(packType);
        populateGeneratedFromRoot(root, packType.m_10305_(), generated, debug, forgetFile, skipFile);
        if (debug) {
            KubeJS.LOGGER.info("Loaded {} generated {} resources", generated.size(), packType);
        }
    }

    public static void populateGeneratedFromRoot(
            Path root,
            String packTypeName,
            Map<ResourceLocation, GeneratedData> generated,
            boolean debug,
            Predicate<String> forgetFile,
            Predicate<GeneratedData> skipFile) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> nsDirs = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path nsDir : nsDirs) {
                String ns = nsDir.getFileName().toString();
                if (debug) {
                    KubeJS.LOGGER.info("Loading generated resources for namespace {}", ns);
                }
                final boolean skipLangSubdir = !debug && canSkipLangDir(ns, skipFile);
                Files.walkFileTree(nsDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (skipLangSubdir && !dir.equals(nsDir)
                                && dir.getParent() != null && dir.getParent().equals(nsDir)
                                && dir.getFileName().toString().equalsIgnoreCase("lang")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (isIgnoredOrHidden(file, attrs)) {
                            return FileVisitResult.CONTINUE;
                        }
                        String relPath = nsDir.relativize(file).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                        int slash = relPath.lastIndexOf('/');
                        String fileName = slash == -1 ? relPath : relPath.substring(slash + 1);
                        if (isSkippedFileName(fileName)) {
                            return FileVisitResult.CONTINUE;
                        }
                        GeneratedData data = new GeneratedData(
                                new ResourceLocation(ns, relPath),
                                Lazy.of(() -> readFileBytes(file)),
                                forgetFile.test(relPath));
                        if (debug) {
                            KubeJS.LOGGER.info("Loaded {}: {} bytes", data.id(), data.data().get().length);
                        }
                        if (skipFile.test(data)) {
                            if (debug) {
                                KubeJS.LOGGER.info("Skipped {}", data.id());
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        generated.put(data.id(), data);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (Exception ex) {
            KubeJS.LOGGER.error("Failed to load " + packTypeName + " from KubeJS", ex);
        }
    }

    private static boolean canSkipLangDir(String ns, Predicate<GeneratedData> skipFile) {
        if (skipFile == null) {
            return false;
        }
        try {
            return skipFile.test(new GeneratedData(new ResourceLocation(ns, "lang/probe.json"), Lazy.of(() -> new byte[0]), false));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isSkippedFileName(String fileName) {
        return fileName.endsWith(".zip")
                || fileName.equals(".ds_store")
                || fileName.equals("thumbs.db")
                || fileName.equals("desktop.ini");
    }

    private static boolean isIgnoredOrHidden(Path file, BasicFileAttributes attrs) throws IOException {
        if (attrs instanceof DosFileAttributes dos) {
            return dos.isHidden();
        }
        return Files.isHidden(file);
    }

    private static byte[] readFileBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (Exception ex) {
            return new byte[0];
        }
    }

    public static void fastScanForInvalidFilesAsync(String pathName, Path path) {
        ForkJoinPool.commonPool().execute(() -> {
            try {
                fastScanForInvalidFiles(pathName, path);
            } catch (IOException e) {
                ConsoleJS.STARTUP.error("Failed to scan for invalid files in " + pathName, e);
            }
        });
    }

    public static void listResources(
            Map<ResourceLocation, GeneratedData> generated,
            String namespace,
            String path,
            PackResources.ResourceOutput output) {
        if (generated == null || generated.isEmpty() || namespace == null || path == null || output == null) {
            return;
        }
        String prefix = path.endsWith("/") ? path : path + "/";
        PackIndex index = getOrBuildIndex(generated);
        NamespaceBucket nsBucket = index.byNamespace.get(namespace);
        if (nsBucket == null) {
            return;
        }
        int slash = prefix.indexOf('/');
        List<Map.Entry<ResourceLocation, GeneratedData>> candidates = slash == 0
                ? nsBucket.all
                : nsBucket.byTopDir.get(slash > 0 ? prefix.substring(0, slash) : "");
        if (candidates == null) {
            return;
        }
        for (int i = 0, n = candidates.size(); i < n; i++) {
            Map.Entry<ResourceLocation, GeneratedData> entry = candidates.get(i);
            if (entry.getKey().m_135815_().startsWith(prefix)) {
                output.accept(entry.getKey(), entry.getValue());
            }
        }
    }

    public static void fastScanForInvalidFiles(String pathName, Path path) throws IOException {
        boolean rootHidden = Files.isHidden(path);
        try (DirectoryStream<Path> topDirs = Files.newDirectoryStream(path, Files::isDirectory)) {
            for (Path subDir : topDirs) {
                Files.walkFileTree(subDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) {
                        if (!attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        validateFile(pathName, path, rootHidden, p);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
    }

    private static void validateFile(String pathName, Path root, boolean rootHidden, Path p) {
        try {
            String fileName = p.getFileName().toString();
            String fileNameLC = fileName.toLowerCase(Locale.ROOT);
            if (fileNameLC.endsWith(".zip")
                    || fileNameLC.equals(".ds_store")
                    || fileNameLC.equals("thumbs.db")
                    || fileNameLC.equals("desktop.ini")) {
                return;
            }
            if (rootHidden) {
                ConsoleJS.STARTUP
                        .error("Invisible file found: " + pathName + root.relativize(p).toString().replace('\\', '/'))
                        .withExternalFile(p);
                return;
            }
            for (int i = 0, len = fileName.length(); i < len; i++) {
                char c = fileName.charAt(i);
                if (c >= 'A' && c <= 'Z') {
                    ConsoleJS.STARTUP
                            .error("Invalid file name: Uppercase '" + c + "' in " + pathName
                                    + root.relativize(p).toString().replace('\\', '/'))
                            .withExternalFile(p);
                    return;
                }
                if (c != '_' && c != '-' && (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '/' && c != '.') {
                    ConsoleJS.STARTUP
                            .error("Invalid file name: Invalid character '" + c + "' in " + pathName
                                    + root.relativize(p).toString().replace('\\', '/'))
                            .withExternalFile(p);
                    return;
                }
            }
        } catch (Exception ex) {
            ConsoleJS.STARTUP
                    .error("Invalid file name: " + pathName + root.relativize(p).toString().replace('\\', '/'), ex)
                    .withExternalFile(p);
        }
    }

    private static PackIndex getOrBuildIndex(Map<ResourceLocation, GeneratedData> generated) {
        int size = generated.size();
        PackEntry[] entries = INDEX_ENTRIES;
        for (int i = 0; i < entries.length; i++) {
            PackEntry e = entries[i];
            if (e.mapRef.get() == generated && e.index.size == size) {
                return e.index;
            }
        }
        synchronized (INDEX_LOCK) {
            entries = INDEX_ENTRIES;
            for (int i = 0; i < entries.length; i++) {
                PackEntry e = entries[i];
                if (e.mapRef.get() == generated && e.index.size == size) {
                    return e.index;
                }
            }
            PackIndex built = buildIndex(generated, size);
            List<PackEntry> updated = new ArrayList<>(entries.length + 1);
            for (PackEntry e : entries) {
                Map<ResourceLocation, GeneratedData> ref = e.mapRef.get();
                if (ref != null && ref != generated) {
                    updated.add(e);
                }
            }
            updated.add(new PackEntry(new WeakReference<>(generated), built));
            INDEX_ENTRIES = updated.toArray(new PackEntry[0]);
            return built;
        }
    }

    private static PackIndex buildIndex(Map<ResourceLocation, GeneratedData> generated, int size) {
        Map<String, NamespaceBucket> byNs = new HashMap<>();
        for (Map.Entry<ResourceLocation, GeneratedData> entry : generated.entrySet()) {
            ResourceLocation loc = entry.getKey();
            NamespaceBucket ns = byNs.computeIfAbsent(loc.m_135827_(), k -> new NamespaceBucket());
            ns.all.add(entry);
            String locPath = loc.m_135815_();
            int slash = locPath.indexOf('/');
            String topDir = slash > 0 ? locPath.substring(0, slash) : "";
            ns.byTopDir.computeIfAbsent(topDir, k -> new ArrayList<>()).add(entry);
        }
        return new PackIndex(size, byNs);
    }

    private static final class PackEntry {
        final WeakReference<Map<ResourceLocation, GeneratedData>> mapRef;
        final PackIndex index;

        PackEntry(WeakReference<Map<ResourceLocation, GeneratedData>> mapRef, PackIndex index) {
            this.mapRef = mapRef;
            this.index = index;
        }
    }

    private static final class PackIndex {
        final int size;
        final Map<String, NamespaceBucket> byNamespace;

        PackIndex(int size, Map<String, NamespaceBucket> byNamespace) {
            this.size = size;
            this.byNamespace = byNamespace;
        }
    }

    private static final class NamespaceBucket {
        final List<Map.Entry<ResourceLocation, GeneratedData>> all = new ArrayList<>();
        final Map<String, List<Map.Entry<ResourceLocation, GeneratedData>>> byTopDir = new HashMap<>();
    }
}
