package dev.sunlit.bettersunlit.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.latvian.mods.kubejs.DevProperties;
import dev.latvian.mods.kubejs.KubeJS;
import dev.latvian.mods.kubejs.KubeJSPaths;
import dev.latvian.mods.kubejs.client.GeneratedClientResourcePack;
import dev.latvian.mods.kubejs.generator.AssetJsonGenerator;
import dev.latvian.mods.kubejs.script.data.GeneratedData;
import dev.latvian.mods.kubejs.script.data.GeneratedResourcePack;
import dev.latvian.mods.kubejs.util.ConsoleJS;
import dev.latvian.mods.kubejs.util.Lazy;
import dev.latvian.mods.rhino.mod.util.JsonUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Lock-free identity-cached index for KubeJS {@code GeneratedResourcePack} lookups and single-pass
 * {@code walkFileTree} validation replacing nested stream/stat scans.
 */
public final class KubeJsPackPerf {
    private static final Pattern UNSUPPORTED_FORMAT_PATTERN = Pattern.compile("%(\\d+\\$)?[\\d.]*[df]");
    private static final Object INDEX_LOCK = new Object();
    private static volatile PackEntry[] INDEX_ENTRIES = new PackEntry[0];
    private static final Object PREFETCH_LOCK = new Object();
    private static final IdentityHashMap<GeneratedResourcePack, CompletableFuture<Void>> PREFETCH_FUTURES =
            new IdentityHashMap<>();
    private static final ThreadLocal<Boolean> IN_PREFETCH = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static volatile CompletableFuture<Map<ResourceLocation, GeneratedData>> EARLY_CLIENT_PREFETCH;
    private static volatile CompletableFuture<Void> EARLY_LANG_PREFETCH;
    private static volatile boolean EARLY_PREFETCH_SCHEDULED;
    private static final ConcurrentHashMap<Path, JsonObject> PRELOADED_LANG_FILES = new ConcurrentHashMap<>(1024);
    private static final ThreadLocal<JsonObject> CURRENT_PRELOADED_LANG = new ThreadLocal<>();
    private static final ConcurrentHashMap<ResourceLocation, JsonObject> DIRECT_LANG_JSON_BY_LOC =
            new ConcurrentHashMap<>(512);

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
        if (EARLY_PREFETCH_SCHEDULED) {
            return;
        }
        synchronized (PREFETCH_LOCK) {
            if (EARLY_PREFETCH_SCHEDULED) {
                return;
            }
            EARLY_PREFETCH_SCHEDULED = true;
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
            EARLY_LANG_PREFETCH = CompletableFuture.runAsync(
                    () -> preloadLangFiles(KubeJSPaths.get(PackType.CLIENT_RESOURCES)),
                    ForkJoinPool.commonPool());
            CompletableFuture.runAsync(() -> {
                try {
                    net.minecraftforge.fml.ModList modList = null;
                    for (int i = 0; i < 500; i++) {
                        modList = net.minecraftforge.fml.ModList.get();
                        if (modList != null && modList.getModFileById("rhino") != null) {
                            break;
                        }
                        modList = null;
                        Thread.sleep(10L);
                    }
                    if (modList != null) {
                        Class<?> cls = Class.forName("dev.latvian.mods.rhino.mod.util.RemappingHelper");
                        cls.getMethod("getMinecraftRemapper").invoke(null);
                    }
                } catch (Throwable ignored) {
                }
            }, ForkJoinPool.commonPool());
        }
    }

    private static String resolveActiveLangJsonName() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
            if (mc != null && mc.f_91066_ != null) {
                String code = mc.f_91066_.f_92075_;
                if (code != null && !code.isEmpty()) {
                    return code.toLowerCase(Locale.ROOT) + ".json";
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isOtherLocaleJson(String fileName, String activeLangJson) {
        if (activeLangJson == null || "en_us.json".equals(fileName) || activeLangJson.equals(fileName)) {
            return false;
        }
        // Standard Minecraft locale JSON names are "<ll>_<rr>.json" (e.g. zh_cn.json, fr_fr.json).
        int len = fileName.length();
        if (len != 10 || fileName.charAt(2) != '_' || !fileName.endsWith(".json")) {
            return false;
        }
        return (fileName.charAt(0) >= 'a' && fileName.charAt(0) <= 'z')
                && (fileName.charAt(1) >= 'a' && fileName.charAt(1) <= 'z')
                && (fileName.charAt(3) >= 'a' && fileName.charAt(3) <= 'z')
                && (fileName.charAt(4) >= 'a' && fileName.charAt(4) <= 'z');
    }

    private static void preloadLangFiles(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        String activeLangJson = resolveActiveLangJsonName();
        List<Path> langFiles = new ArrayList<>(1024);
        try (DirectoryStream<Path> nsDirs = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path nsDir : nsDirs) {
                Path langDir = nsDir.resolve("lang");
                if (!Files.isDirectory(langDir)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(langDir)) {
                    for (Path file : files) {
                        if (Files.isRegularFile(file)) {
                            String name = file.getFileName().toString();
                            if (name.endsWith(".json") && !isOtherLocaleJson(name, activeLangJson)) {
                                langFiles.add(file.toAbsolutePath().normalize());
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        if (langFiles.isEmpty()) {
            return;
        }
        langFiles.parallelStream().forEach(file -> {
            try (BufferedReader reader = Files.newBufferedReader(file)) {
                JsonObject obj = JsonUtils.GSON.fromJson(reader, JsonObject.class);
                if (obj != null) {
                    PRELOADED_LANG_FILES.put(file, obj);
                }
            } catch (Exception ignored) {
            }
        });
    }

    public static BufferedReader openLangBufferedReader(Path file) throws IOException {
        CompletableFuture<Void> langFuture = EARLY_LANG_PREFETCH;
        if (langFuture != null) {
            EARLY_LANG_PREFETCH = null;
            try {
                langFuture.join();
            } catch (RuntimeException ignored) {
            }
        }
        if (file != null && !PRELOADED_LANG_FILES.isEmpty()) {
            JsonObject preloaded = PRELOADED_LANG_FILES.remove(file.toAbsolutePath().normalize());
            if (preloaded != null) {
                CURRENT_PRELOADED_LANG.set(preloaded);
                return null;
            }
        }
        return Files.newBufferedReader(file);
    }

    @SuppressWarnings("unchecked")
    public static <T> T parseLangJson(Gson gson, Reader reader, Class<T> type) {
        if (reader == null) {
            JsonObject preloaded = CURRENT_PRELOADED_LANG.get();
            CURRENT_PRELOADED_LANG.remove();
            return (T) preloaded;
        }
        return gson.fromJson(reader, type);
    }

    public static void registerGeneratedLangJson(
            AssetJsonGenerator generator, ResourceLocation id, JsonElement json) {
        if (id != null && json instanceof JsonObject obj) {
            DIRECT_LANG_JSON_BY_LOC.put(
                    new ResourceLocation(id.m_135827_(), id.m_135815_() + ".json"),
                    obj);
        }
        generator.json(id, json);
    }

    public static boolean tryLoadDirectLang(
            Resource resource, ResourceLocation location, Map<String, String> target) {
        if (resource == null || location == null || target == null
                || !(resource.m_247173_() instanceof GeneratedClientResourcePack)) {
            return false;
        }
        JsonObject obj = DIRECT_LANG_JSON_BY_LOC.get(location);
        if (obj == null) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String key = entry.getKey();
            String raw = GsonHelper.m_13805_(entry.getValue(), key);
            String val = raw.indexOf('%') >= 0
                    ? UNSUPPORTED_FORMAT_PATTERN.matcher(raw).replaceAll("%$1s")
                    : raw;
            target.put(key, val);
        }
        return true;
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
