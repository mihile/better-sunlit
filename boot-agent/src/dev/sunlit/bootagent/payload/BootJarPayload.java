package dev.sunlit.bootagent.payload;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

/**
 * Early bootstrap helpers for fast parallel JAR discovery and single-pass ZipFile metadata loading.
 * Uses only {@code java.base} types in signatures so it is safe across any child classloader.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class BootJarPayload {
    private static final String SVC_TRANSFORMER =
            "META-INF/services/cpw.mods.modlauncher.api.ITransformationService";
    private static final String SVC_MOD_LOCATOR =
            "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator";
    private static final String SVC_DEP_LOCATOR =
            "META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator";
    private static final boolean[] VALID_RES_PATH = new boolean[128];
    private static final byte[] ONLYIN_NEEDLE =
            "net/minecraftforge/api/distmarker/OnlyIn".getBytes(StandardCharsets.US_ASCII);
    private static final int[] ONLYIN_SHIFT = buildBmhShift(ONLYIN_NEEDLE);
    private static final byte[] SUBSCRIBE_NEEDLE =
            "net/minecraftforge/eventbus/api/SubscribeEvent".getBytes(StandardCharsets.US_ASCII);
    private static final int[] SUBSCRIBE_SHIFT = buildBmhShift(SUBSCRIBE_NEEDLE);
    private static final byte[] EXT_ENUM_NEEDLE =
            "net/minecraftforge/common/IExtensibleEnum".getBytes(StandardCharsets.US_ASCII);
    private static final int[] EXT_ENUM_SHIFT = buildBmhShift(EXT_ENUM_NEEDLE);
    private static final byte[] OBJ_HOLDER_NEEDLE =
            "net/minecraftforge/registries/ObjectHolder".getBytes(StandardCharsets.US_ASCII);
    private static final int[] OBJ_HOLDER_SHIFT = buildBmhShift(OBJ_HOLDER_NEEDLE);
    private static final byte[] CAP_TOKEN_NEEDLE =
            "net/minecraftforge/common/capabilities/CapabilityToken".getBytes(StandardCharsets.US_ASCII);
    private static final int[] CAP_TOKEN_SHIFT = buildBmhShift(CAP_TOKEN_NEEDLE);
    private static final byte[] FORGE_ONLYIN_SUFFIX =
            "api/distmarker/OnlyIn".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FORGE_SUBSCRIBE_SUFFIX =
            "eventbus/api/SubscribeEvent".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FORGE_EXT_ENUM_SUFFIX =
            "common/IExtensibleEnum".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FORGE_OBJ_HOLDER_SUFFIX =
            "registries/ObjectHolder".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FORGE_CAP_TOKEN_SUFFIX =
            "common/capabilities/CapabilityToken".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EVENT_CLASS_BYTES =
            "net/minecraftforge/eventbus/api/Event".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OBJECT_CLASS_BYTES =
            "java/lang/Object".getBytes(StandardCharsets.US_ASCII);
    private static final byte[][] NON_EVENT_SUPER_PREFIXES = {
            "java/".getBytes(StandardCharsets.US_ASCII),
            "javax/".getBytes(StandardCharsets.US_ASCII),
            "jdk/".getBytes(StandardCharsets.US_ASCII),
            "sun/".getBytes(StandardCharsets.US_ASCII),
            "net/minecraft/".getBytes(StandardCharsets.US_ASCII),
            "com/mojang/".getBytes(StandardCharsets.US_ASCII),
            "com/google/".getBytes(StandardCharsets.US_ASCII),
            "it/unimi/".getBytes(StandardCharsets.US_ASCII),
            "io/netty/".getBytes(StandardCharsets.US_ASCII),
            "org/apache/".getBytes(StandardCharsets.US_ASCII),
            "org/lwjgl/".getBytes(StandardCharsets.US_ASCII),
            "org/slf4j/".getBytes(StandardCharsets.US_ASCII),
            "org/objectweb/".getBytes(StandardCharsets.US_ASCII),
            "org/spongepowered/".getBytes(StandardCharsets.US_ASCII),
            "cpw/mods/".getBytes(StandardCharsets.US_ASCII)
    };
    private static volatile Object distCleanerPlugin;
    private static volatile Object eventBusPlugin;
    private static volatile Object enumExtenderPlugin;
    private static volatile Object objectHolderPlugin;
    private static volatile Object capabilityTokenPlugin;
    private static volatile ExecutorService packCachePool;
    private static final ConcurrentHashMap<String, Boolean> NON_EVENT_SUPERS =
            new ConcurrentHashMap<>(16384, 0.75f, 16);
    private static final ConcurrentHashMap<String, String> KNOWN_SUPER_BY_CLASS =
            new ConcurrentHashMap<>(65536, 0.75f, 16);
    private static final ConcurrentHashMap<String, SoftReference<byte[]>> NESTED_JAR_BYTES = new ConcurrentHashMap<>(256);
    private static final ThreadLocal<boolean[]> RESOLVING_SUPER = ThreadLocal.withInitial(() -> new boolean[1]);
    private static final ThreadLocal<boolean[]> FAST_CLASSINFO = ThreadLocal.withInitial(() -> new boolean[1]);
    private static final ThreadLocal<CpScratch> CP_SCRATCH = ThreadLocal.withInitial(CpScratch::new);
    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private static final class CpScratch {
        int[] utfOff = new int[512];
        int[] utfLen = new int[512];
        int[] clsNameIdx = new int[512];

        void ensure(int cpCount) {
            if (cpCount > utfOff.length) {
                int cap = Math.max(cpCount, utfOff.length << 1);
                utfOff = new int[cap];
                utfLen = new int[cap];
                clsNameIdx = new int[cap];
            }
        }
    }
    static {
        NON_EVENT_SUPERS.put("java/lang/Object", Boolean.TRUE);
        NON_EVENT_SUPERS.put("java/lang/Enum", Boolean.TRUE);
        NON_EVENT_SUPERS.put("java/lang/Record", Boolean.TRUE);
        NON_EVENT_SUPERS.put("java/lang/Throwable", Boolean.TRUE);
        NON_EVENT_SUPERS.put("net/minecraftforge/eventbus/api/Event", Boolean.FALSE);
        for (char c = 'a'; c <= 'z'; c++) VALID_RES_PATH[c] = true;
        for (char c = '0'; c <= '9'; c++) VALID_RES_PATH[c] = true;
        VALID_RES_PATH['_'] = true;
        VALID_RES_PATH['.'] = true;
        VALID_RES_PATH['-'] = true;
        VALID_RES_PATH['/'] = true;
    }

    private static int[] buildBmhShift(byte[] needle) {
        int[] shift = new int[256];
        for (int i = 0; i < shift.length; i++) {
            shift[i] = needle.length;
        }
        for (int i = 0; i < needle.length - 1; i++) {
            shift[needle[i] & 0xFF] = needle.length - 1 - i;
        }
        return shift;
    }

    private BootJarPayload() {
    }

    public static boolean isDefaultRegularJar(Path[] paths) {
        if (paths == null || paths.length != 1) {
            return false;
        }
        Path p = paths[0];
        return p != null && p.getFileSystem() == FileSystems.getDefault() && Files.isRegularFile(p);
    }

    public static Object[] tryFastSingleJar(Path[] paths, BiPredicate pathFilter) {
        if (paths == null || paths.length != 1) {
            return null;
        }
        return fastReadSingleJar(paths[0], pathFilter);
    }

    public static List<Path> filterServiceJars(Path modsDir) {
        List<Path> candidates = new ArrayList<>();
        long[] sizes = new long[512];
        long[] mtimes = new long[512];
        String[] names = new String[512];
        int count = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir)) {
            for (Path p : ds) {
                Path fn = p.getFileName();
                if (fn == null) {
                    continue;
                }
                String name = fn.toString();
                if (!name.endsWith(".jar")) {
                    continue;
                }
                try {
                    BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                    if (!attrs.isRegularFile()) {
                        continue;
                    }
                    long sz = attrs.size();
                    if (sz <= 0L) {
                        continue;
                    }
                    if (count == sizes.length) {
                        int next = count << 1;
                        sizes = Arrays.copyOf(sizes, next);
                        mtimes = Arrays.copyOf(mtimes, next);
                        names = Arrays.copyOf(names, next);
                    }
                    candidates.add(p);
                    sizes[count] = sz;
                    mtimes[count] = attrs.lastModifiedTime().toMillis();
                    names[count] = name;
                    count++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return List.of();
        }
        if (count == 0) {
            return List.of();
        }

        // Load persistent disk cache keyed by (filename, size, mtime) to avoid opening ~300 JARs on every boot.
        Path parentDir = modsDir.getParent();
        Path cacheFile = parentDir != null ? parentDir.resolve("config").resolve(".sunlit-service-jars.cache") : null;
        HashMap<String, String> cache = null;
        if (cacheFile != null && Files.isRegularFile(cacheFile)) {
            try {
                List<String> lines = Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
                cache = new HashMap<>(Math.max(16, lines.size() * 2));
                for (int i = 0, n = lines.size(); i < n; i++) {
                    String line = lines.get(i);
                    int t1 = line.indexOf('\t');
                    if (t1 > 0) {
                        cache.put(line.substring(0, t1), line.substring(t1 + 1));
                    }
                }
            } catch (Throwable ignored) {
                cache = null;
            }
        }

        boolean[] keep = new boolean[count];
        int[] missIndices = new int[count];
        int missCount = 0;
        for (int i = 0; i < count; i++) {
            String cached = cache != null ? cache.get(names[i]) : null;
            if (cached != null) {
                String expectedPrefix = sizes[i] + "\t" + mtimes[i] + "\t";
                if (cached.length() == expectedPrefix.length() + 1 && cached.startsWith(expectedPrefix)) {
                    char flag = cached.charAt(expectedPrefix.length());
                    if (flag == '0' || flag == '1') {
                        keep[i] = (flag == '1');
                        continue;
                    }
                }
            }
            missIndices[missCount++] = i;
        }

        if (missCount > 0) {
            if (missCount <= 4) {
                for (int m = 0; m < missCount; m++) {
                    int idx = missIndices[m];
                    keep[idx] = mightHaveDiscoveryService(candidates.get(idx));
                }
            } else {
                int workers = Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors()));
                ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
                    Thread t = new Thread(r, "Sunlit-JarDiscover");
                    t.setDaemon(true);
                    return t;
                });
                try {
                    List<Callable<Void>> tasks = new ArrayList<>(workers);
                    int chunk = (missCount + workers - 1) / workers;
                    for (int start = 0; start < missCount; start += chunk) {
                        final int s = start;
                        final int e = Math.min(missCount, start + chunk);
                        tasks.add(() -> {
                            for (int m = s; m < e; m++) {
                                int idx = missIndices[m];
                                keep[idx] = mightHaveDiscoveryService(candidates.get(idx));
                            }
                            return null;
                        });
                    }
                    List<Future<Void>> futures = pool.invokeAll(tasks);
                    for (Future<Void> f : futures) {
                        f.get();
                    }
                } catch (Throwable t) {
                    // Fall back conservatively: let ModDirTransformerDiscoverer scan all candidates.
                    return candidates;
                } finally {
                    pool.shutdown();
                }
            }
        }

        if (cacheFile != null && (missCount > 0 || cache == null || cache.size() != count)) {
            try {
                Path cfgDir = cacheFile.getParent();
                if (cfgDir != null && Files.isDirectory(cfgDir)) {
                    StringBuilder sb = new StringBuilder(count * 48);
                    for (int i = 0; i < count; i++) {
                        sb.append(names[i]).append('\t')
                                .append(sizes[i]).append('\t')
                                .append(mtimes[i]).append('\t')
                                .append(keep[i] ? '1' : '0').append('\n');
                    }
                    Path tmp = cfgDir.resolve(".sunlit-service-jars.cache.tmp");
                    Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                    Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Throwable ignored) {
            }
        }

        List<Path> matched = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (keep[i]) {
                matched.add(candidates.get(i));
            }
        }
        return matched;
    }

    private static boolean mightHaveDiscoveryService(Path jarPath) {
        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            if (zf.getEntry("module-info.class") != null
                    || zf.getEntry(SVC_TRANSFORMER) != null
                    || zf.getEntry(SVC_MOD_LOCATOR) != null
                    || zf.getEntry(SVC_DEP_LOCATOR) != null) {
                return true;
            }
            ZipEntry mf = zf.getEntry("META-INF/MANIFEST.MF");
            if (mf != null && mf.getSize() > 0 && mf.getSize() < 1048576) {
                try (InputStream is = zf.getInputStream(mf)) {
                    String text = new String(is.readAllBytes(), StandardCharsets.ISO_8859_1);
                    if (text.contains("Multi-Release")) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Reads manifest, package set, and raw service providers from an unsigned single-file JAR
     * in one central-directory pass. Returns {@code null} when the JAR is signed, unreadable,
     * or requires {@code JarInputStream} fallback.
     *
     * @return {@code Object[]{Manifest (nullable), Set<String> packages (nullable), List<Object[]> providers (nullable)}}
     */
    public static Object[] fastReadSingleJar(Path jarPath, BiPredicate pathFilter) {
        if (jarPath == null || jarPath.getFileSystem() != FileSystems.getDefault() || !Files.isRegularFile(jarPath)) {
            return null;
        }
        boolean allowEntryCache = pathFilter == null
                || pathFilter.getClass().getName().startsWith("net.minecraftforge.fml.loading.moddiscovery.AbstractModProvider");
        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            ZipEntry mfEntry = null;
            boolean hasVersions = false;
            HashSet<String> seenSlashPkgs = allowEntryCache ? new HashSet<>(64) : null;
            String lastSlashPkg = null;
            List<ZipEntry> serviceEntries = null;

            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (name.startsWith("META-INF/")) {
                    if (isSignatureEntry(name)) {
                        return null;
                    }
                    if ("META-INF/MANIFEST.MF".equals(name)) {
                        mfEntry = e;
                    } else if (name.startsWith("META-INF/versions/")) {
                        hasVersions = true;
                    } else if (allowEntryCache && !e.isDirectory() && name.startsWith("META-INF/services/")
                            && name.length() > "META-INF/services/".length()) {
                        if (serviceEntries == null) {
                            serviceEntries = new ArrayList<>(4);
                        }
                        serviceEntries.add(e);
                    }
                } else if (allowEntryCache && !e.isDirectory() && name.endsWith(".class")) {
                    int slash = name.lastIndexOf('/');
                    if (slash > 0) {
                        if (lastSlashPkg != null
                                && slash == lastSlashPkg.length()
                                && name.regionMatches(0, lastSlashPkg, 0, slash)) {
                            continue;
                        }
                        String raw = name.substring(0, slash);
                        lastSlashPkg = raw;
                        seenSlashPkgs.add(raw);
                    }
                }
            }

            Manifest mf = null;
            boolean isMultiRelease = false;
            if (mfEntry != null) {
                try (InputStream is = zf.getInputStream(mfEntry)) {
                    mf = new Manifest(is);
                }
                recordJarManifest(jarPath, mf);
                isMultiRelease = Boolean.parseBoolean(mf.getMainAttributes().getValue("Multi-Release"));
            }

            if (isMultiRelease || hasVersions || !allowEntryCache) {
                return new Object[]{mf, null, null};
            }

            Set<String> packages = new HashSet<>(seenSlashPkgs.size() * 2 + 16);
            for (String raw : seenSlashPkgs) {
                packages.add(raw.replace('/', '.'));
            }
            List<Object[]> rawProviders;
            if (serviceEntries == null) {
                rawProviders = List.of();
            } else {
                rawProviders = new ArrayList<>(serviceEntries.size());
                for (int i = 0; i < serviceEntries.size(); i++) {
                    ZipEntry se = serviceEntries.get(i);
                    String full = se.getName();
                    int slash = full.lastIndexOf('/');
                    String serviceName = slash >= 0 ? full.substring(slash + 1) : full;
                    List<String> impls = new ArrayList<>(4);
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(zf.getInputStream(se), StandardCharsets.UTF_8))) {
                        for (String line; (line = br.readLine()) != null; ) {
                            String trimmed = line.trim();
                            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                                impls.add(trimmed);
                            }
                        }
                    }
                    rawProviders.add(new Object[]{serviceName, List.copyOf(impls)});
                }
                rawProviders = List.copyOf(rawProviders);
            }
            return new Object[]{mf, packages, rawProviders};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isSignatureEntry(String name) {
        if (name.indexOf('/', 9) >= 0) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC");
    }

    public static List mapStreamInParallel(Stream stream, Function fn) {
        List items = stream.toList();
        int count = items.size();
        if (count <= 4) {
            List out = new ArrayList(count);
            for (int i = 0; i < count; i++) {
                out.add(fn.apply(items.get(i)));
            }
            return List.copyOf(out);
        }
        Object[] out = new Object[count];
        int workers = Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "Sunlit-ModScan");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Callable<Void>> tasks = new ArrayList<>(workers);
            int chunk = (count + workers - 1) / workers;
            for (int start = 0; start < count; start += chunk) {
                final int s = start;
                final int e = Math.min(count, start + chunk);
                tasks.add(() -> {
                    for (int i = s; i < e; i++) {
                        out[i] = fn.apply(items.get(i));
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> f : futures) {
                f.get();
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        } finally {
            pool.shutdown();
        }
        return List.of(out);
    }

    /**
     * Fast ASCII validation for {@code PackResourcesCacheEngine.isValidCachedResourcePath}.
     * Returns {@code 1} valid, {@code 0} invalid, or {@code -1} when fallback to Mixin-hooked
     * {@code ResourceLocation.isValidPathChar} is required.
     */
    public static int fastCheckResourcePath(Path path) {
        if (path == null || path.getFileName() == null) {
            return 0;
        }
        int count = path.getNameCount();
        if (count == 0) {
            return 0;
        }
        boolean anyNonEmpty = count > 1;
        for (int i = 0; i < count; i++) {
            String seg = path.getName(i).toString();
            int len = seg.length();
            if (len > 0) {
                anyNonEmpty = true;
                for (int j = 0; j < len; j++) {
                    char c = seg.charAt(j);
                    if (c >= 128 || !VALID_RES_PATH[c]) {
                        return -1;
                    }
                }
            }
        }
        return anyNonEmpty ? 1 : 0;
    }

    private static ExecutorService getPackCachePool() {
        ExecutorService p = packCachePool;
        if (p != null) {
            return p;
        }
        synchronized (BootJarPayload.class) {
            p = packCachePool;
            if (p == null) {
                int threads = Math.min(10, Math.max(4, Runtime.getRuntime().availableProcessors() / 2));
                p = Executors.newFixedThreadPool(threads, r -> {
                    Thread t = new Thread(r, "Sunlit-PackCache");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY);
                    return t;
                });
                packCachePool = p;
            }
            return p;
        }
    }

    public static void submitPackCacheWarmup(Runnable engineTask) {
        if (engineTask == null) {
            return;
        }
        try {
            getPackCachePool().execute(engineTask);
        } catch (Throwable ignored) {
        }
    }

    public static void runParallelRange(int count, IntConsumer task) {
        if (count <= 0) {
            return;
        }
        if (count < 32 || ForkJoinPool.getCommonPoolParallelism() <= 1) {
            for (int i = 0; i < count; i++) {
                task.accept(i);
            }
            return;
        }
        int workers = Math.min(8, ForkJoinPool.getCommonPoolParallelism() + 1);
        int chunk = (count + workers - 1) / workers;
        List<ForkJoinTask<?>> tasks = new ArrayList<>(workers);
        for (int start = chunk; start < count; start += chunk) {
            final int s = start;
            final int e = Math.min(count, start + chunk);
            tasks.add(ForkJoinPool.commonPool().submit(() -> {
                for (int i = s; i < e; i++) {
                    task.accept(i);
                }
            }));
        }
        int firstEnd = Math.min(count, chunk);
        for (int i = 0; i < firstEnd; i++) {
            task.accept(i);
        }
        for (int i = 0; i < tasks.size(); i++) {
            tasks.get(i).join();
        }
    }

    /**
     * Returns {@code true} when {@code plugins} contains only no-op Forge plugins
     * ({@code RuntimeDistCleaner} without {@code @OnlyIn}/{@code @OnlyIns},
     * {@code EventBus} {@code AFTER}-phase without {@code @SubscribeEvent} and with a known non-Event super,
     * {@code RuntimeEnumExtender} without {@code IExtensibleEnum},
     * {@code ObjectHolderDefinalize} without {@code @ObjectHolder}, and/or
     * {@code CapabilityTokenSubclass} without {@code CapabilityToken}).
     */
    public static void enterFastClassInfo() {
        FAST_CLASSINFO.get()[0] = true;
    }

    public static void exitFastClassInfo() {
        FAST_CLASSINFO.get()[0] = false;
    }

    public static boolean consumeFastClassInfo() {
        boolean[] flag = FAST_CLASSINFO.get();
        boolean v = flag[0];
        if (v) {
            flag[0] = false;
        }
        return v;
    }

    public static boolean canSkipDistCleanerOnly(Map plugins, byte[] bytes) {
        return canSkipDistCleanerOnly(plugins, bytes, null);
    }

    public static boolean canSkipDistCleanerOnly(Map plugins, byte[] bytes, ClassLoader loader) {
        if (plugins == null || plugins.isEmpty() || bytes == null) {
            return false;
        }
        boolean hasDist = false;
        boolean hasEventBus = false;
        boolean hasEnumExt = false;
        boolean hasObjHolder = false;
        boolean hasCapToken = false;
        Iterator it = plugins.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Map.Entry) it.next();
            List list = (List) entry.getValue();
            if (list == null || list.isEmpty()) {
                continue;
            }
            Object phaseObj = entry.getKey();
            boolean isAfter = phaseObj instanceof Enum<?> en && "AFTER".equals(en.name());
            for (int i = 0, n = list.size(); i < n; i++) {
                Object plugin = list.get(i);
                if (plugin == null) {
                    return false;
                }
                if (plugin == distCleanerPlugin) {
                    hasDist = true;
                } else if (plugin == eventBusPlugin) {
                    if (!isAfter) {
                        return false;
                    }
                    hasEventBus = true;
                } else if (plugin == enumExtenderPlugin) {
                    if (!isAfter) {
                        return false;
                    }
                    hasEnumExt = true;
                } else if (plugin == objectHolderPlugin) {
                    if (!isAfter) {
                        return false;
                    }
                    hasObjHolder = true;
                } else if (plugin == capabilityTokenPlugin) {
                    if (!isAfter) {
                        return false;
                    }
                    hasCapToken = true;
                } else {
                    String name = plugin.getClass().getName();
                    if ("net.minecraftforge.fml.loading.RuntimeDistCleaner".equals(name)) {
                        distCleanerPlugin = plugin;
                        hasDist = true;
                    } else if ("net.minecraftforge.eventbus.service.ModLauncherService".equals(name)) {
                        eventBusPlugin = plugin;
                        if (!isAfter) {
                            return false;
                        }
                        hasEventBus = true;
                    } else if ("net.minecraftforge.fml.common.asm.RuntimeEnumExtender".equals(name)) {
                        enumExtenderPlugin = plugin;
                        if (!isAfter) {
                            return false;
                        }
                        hasEnumExt = true;
                    } else if ("net.minecraftforge.fml.common.asm.ObjectHolderDefinalize".equals(name)) {
                        objectHolderPlugin = plugin;
                        if (!isAfter) {
                            return false;
                        }
                        hasObjHolder = true;
                    } else if ("net.minecraftforge.fml.common.asm.CapabilityTokenSubclass".equals(name)) {
                        capabilityTokenPlugin = plugin;
                        if (!isAfter) {
                            return false;
                        }
                        hasCapToken = true;
                    } else {
                        return false;
                    }
                }
            }
        }
        int cpEnd = findConstantPoolEnd(bytes);
        int scanStart = cpEnd >= 10 ? 10 : 0;
        int scanEnd = cpEnd >= 10 ? cpEnd : bytes.length;
        if (hasDist && containsBytesRange(bytes, scanStart, scanEnd, ONLYIN_NEEDLE, ONLYIN_SHIFT)) {
            return false;
        }
        if (hasEventBus) {
            if (containsBytesRange(bytes, scanStart, scanEnd, SUBSCRIBE_NEEDLE, SUBSCRIBE_SHIFT)) {
                extractNonPrefixSuperName(bytes);
                return false;
            }
            if (!isKnownNonEventClass(bytes, loader)) {
                return false;
            }
        }
        if (hasEnumExt && containsBytesRange(bytes, scanStart, scanEnd, EXT_ENUM_NEEDLE, EXT_ENUM_SHIFT)) {
            return false;
        }
        if (hasObjHolder && containsBytesRange(bytes, scanStart, scanEnd, OBJ_HOLDER_NEEDLE, OBJ_HOLDER_SHIFT)) {
            return false;
        }
        if (hasCapToken && containsBytesRange(bytes, scanStart, scanEnd, CAP_TOKEN_NEEDLE, CAP_TOKEN_SHIFT)) {
            return false;
        }
        return true;
    }

    private static int findConstantPoolEnd(byte[] b) {
        int n = b.length;
        if (n < 16
                || (b[0] & 0xFF) != 0xCA || (b[1] & 0xFF) != 0xFE
                || (b[2] & 0xFF) != 0xBA || (b[3] & 0xFF) != 0xBE) {
            return -1;
        }
        int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
        int pos = 10;
        for (int idx = 1; idx < cpCount; idx++) {
            if (pos >= n) {
                return -1;
            }
            int tag = b[pos++] & 0xFF;
            switch (tag) {
                case 1 -> {
                    if (pos + 2 > n) return -1;
                    int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                    pos += 2 + len;
                    if (pos > n) return -1;
                }
                case 7, 8, 16, 19, 20 -> pos += 2;
                case 3, 4, 9, 10, 11, 12, 17, 18 -> pos += 4;
                case 5, 6 -> {
                    pos += 8;
                    idx++;
                }
                case 15 -> pos += 3;
                default -> {
                    return -1;
                }
            }
        }
        return pos <= n ? pos : -1;
    }

    private static boolean containsBytesRange(byte[] haystack, int start, int end, byte[] needle, int[] shift) {
        int m = needle.length;
        if (end - start < m) {
            return false;
        }
        int last = m - 1;
        byte lastByte = needle[last];
        int i = start;
        int limit = end - m;
        while (i <= limit) {
            byte b = haystack[i + last];
            if (b == lastByte) {
                int j = last - 1;
                while (j >= 0 && haystack[i + j] == needle[j]) {
                    j--;
                }
                if (j < 0) {
                    return true;
                }
            }
            i += shift[b & 0xFF];
        }
        return false;
    }

    public static SeekableByteChannel openCachedReadChannel(Path path) throws IOException {
        if (path == null || path.getFileSystem() == FileSystems.getDefault()) {
            return null;
        }
        String pStr = path.toString();
        if (!pStr.endsWith(".jar")) {
            return null;
        }
        String key = path.getFileSystem().toString() + "!" + pStr;
        SoftReference<byte[]> ref = NESTED_JAR_BYTES.get(key);
        byte[] data = ref != null ? ref.get() : null;
        if (data == null) {
            data = readAllBytesExact(path);
            if (NESTED_JAR_BYTES.size() < 512) {
                NESTED_JAR_BYTES.put(key, new SoftReference<>(data));
            }
        }
        return new ByteArraySeekableChannel(data);
    }

    private static final class ByteArraySeekableChannel implements SeekableByteChannel {
        private final byte[] data;
        private int pos;
        private volatile boolean open = true;

        ByteArraySeekableChannel(byte[] data) {
            this.data = data;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            if (pos >= data.length) {
                return -1;
            }
            int avail = data.length - pos;
            int n = Math.min(avail, dst.remaining());
            dst.put(data, pos, n);
            pos += n;
            return n;
        }

        @Override
        public int write(ByteBuffer src) {
            throw new NonWritableChannelException();
        }

        @Override
        public long position() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            return pos;
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            if (newPosition < 0L || newPosition > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("position=" + newPosition);
            }
            this.pos = (int) newPosition;
            return this;
        }

        @Override
        public long size() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            return data.length;
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new NonWritableChannelException();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    private static boolean isKnownNonEventClass(byte[] b, ClassLoader loader) {
        String superName = extractNonPrefixSuperName(b);
        if (superName == null) {
            return false;
        }
        if (superName.isEmpty()) {
            return true;
        }
        Boolean cached = NON_EVENT_SUPERS.get(superName);
        if (cached != null) {
            return cached.booleanValue();
        }
        if (loader == null) {
            return false;
        }
        boolean[] active = RESOLVING_SUPER.get();
        if (active[0]) {
            return false;
        }
        active[0] = true;
        try {
            ArrayList<String> chain = new ArrayList<>(4);
            String cur = superName;
            Boolean result = null;
            for (int depth = 0; depth < 24 && cur != null; depth++) {
                Boolean known = NON_EVENT_SUPERS.get(cur);
                if (known != null) {
                    result = known;
                    break;
                }
                if (isKnownSafePrefix(cur)) {
                    NON_EVENT_SUPERS.put(cur, Boolean.TRUE);
                    result = Boolean.TRUE;
                    break;
                }
                if (chain.contains(cur)) {
                    break;
                }
                chain.add(cur);
                String next = KNOWN_SUPER_BY_CLASS.get(cur);
                if (next == null) {
                    byte[] parentBytes = readClassHeaderBytes(loader, cur + ".class");
                    if (parentBytes == null) {
                        break;
                    }
                    next = extractSuperNameRaw(parentBytes);
                    if (next == null) {
                        break;
                    }
                    if (KNOWN_SUPER_BY_CLASS.size() < 32768) {
                        KNOWN_SUPER_BY_CLASS.putIfAbsent(cur, next);
                    }
                }
                if (next.isEmpty()) {
                    result = Boolean.TRUE;
                    break;
                }
                cur = next;
            }
            Boolean cacheVal = result != null ? result : Boolean.FALSE;
            for (int i = 0, n = chain.size(); i < n; i++) {
                NON_EVENT_SUPERS.put(chain.get(i), cacheVal);
            }
            return cacheVal.booleanValue();
        } finally {
            active[0] = false;
        }
    }

    private static boolean isKnownSafePrefix(String name) {
        if ("java/lang/Object".equals(name)) {
            return true;
        }
        return name.startsWith("java/")
                || name.startsWith("javax/")
                || name.startsWith("jdk/")
                || name.startsWith("sun/")
                || name.startsWith("net/minecraft/")
                || name.startsWith("com/mojang/")
                || name.startsWith("org/spongepowered/")
                || name.startsWith("org/objectweb/")
                || name.startsWith("cpw/mods/");
    }

    private static byte[] readClassHeaderBytes(ClassLoader loader, String resName) {
        try (InputStream in = loader.getResourceAsStream(resName)) {
            if (in == null) {
                return null;
            }
            byte[] buf = new byte[4096];
            int len = 0;
            while (true) {
                int r = in.read(buf, len, buf.length - len);
                if (r < 0) {
                    break;
                }
                len += r;
                if (len >= 16 && hasCompleteHeader(buf, len)) {
                    break;
                }
                if (len == buf.length) {
                    if (buf.length >= 65536) {
                        byte[] rest = in.readAllBytes();
                        byte[] bigger = new byte[len + rest.length];
                        System.arraycopy(buf, 0, bigger, 0, len);
                        System.arraycopy(rest, 0, bigger, len, rest.length);
                        return bigger;
                    }
                    byte[] nextBuf = new byte[buf.length * 2];
                    System.arraycopy(buf, 0, nextBuf, 0, len);
                    buf = nextBuf;
                }
            }
            if (len < 16) {
                return null;
            }
            if (len == buf.length) {
                return buf;
            }
            byte[] trimmed = new byte[len];
            System.arraycopy(buf, 0, trimmed, 0, len);
            return trimmed;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasCompleteHeader(byte[] b, int n) {
        if (n < 16
                || (b[0] & 0xFF) != 0xCA || (b[1] & 0xFF) != 0xFE
                || (b[2] & 0xFF) != 0xBA || (b[3] & 0xFF) != 0xBE) {
            return false;
        }
        int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
        int pos = 10;
        for (int idx = 1; idx < cpCount; idx++) {
            if (pos >= n) {
                return false;
            }
            int tag = b[pos++] & 0xFF;
            switch (tag) {
                case 1 -> {
                    if (pos + 2 > n) return false;
                    int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                    pos += 2 + len;
                    if (pos > n) return false;
                }
                case 7, 8, 16, 19, 20 -> pos += 2;
                case 3, 4, 9, 10, 11, 12, 17, 18 -> pos += 4;
                case 5, 6 -> {
                    pos += 8;
                    idx++;
                }
                case 15 -> pos += 3;
                default -> {
                    return false;
                }
            }
        }
        return pos + 6 <= n;
    }

    /** Returns {@code ""} if matched fast non-event prefix, {@code null} if Event/invalid, or {@code superName}. */
    private static String extractNonPrefixSuperName(byte[] b) {
        return parseSuperName(b, true);
    }

    private static String extractSuperNameRaw(byte[] b) {
        return parseSuperName(b, false);
    }

    private static String parseSuperName(byte[] b, boolean checkPrefixFast) {
        int n = b.length;
        if (n < 16
                || (b[0] & 0xFF) != 0xCA || (b[1] & 0xFF) != 0xFE
                || (b[2] & 0xFF) != 0xBA || (b[3] & 0xFF) != 0xBE) {
            return null;
        }
        int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
        if (cpCount < 4) {
            return null;
        }
        CpScratch scratch = CP_SCRATCH.get();
        scratch.ensure(cpCount);
        int[] utfOff = scratch.utfOff;
        int[] utfLen = scratch.utfLen;
        int[] clsNameIdx = scratch.clsNameIdx;
        int pos = 10;
        for (int idx = 1; idx < cpCount; idx++) {
            if (pos >= n) {
                return null;
            }
            int tag = b[pos++] & 0xFF;
            switch (tag) {
                case 1 -> {
                    if (pos + 2 > n) return null;
                    int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                    pos += 2;
                    if (pos + len > n) return null;
                    utfOff[idx] = pos;
                    utfLen[idx] = len;
                    clsNameIdx[idx] = 0;
                    pos += len;
                }
                case 7 -> {
                    if (pos + 2 > n) return null;
                    utfOff[idx] = 0;
                    utfLen[idx] = 0;
                    clsNameIdx[idx] = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                    pos += 2;
                }
                case 8, 16, 19, 20 -> {
                    utfOff[idx] = 0;
                    clsNameIdx[idx] = 0;
                    pos += 2;
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> {
                    utfOff[idx] = 0;
                    clsNameIdx[idx] = 0;
                    pos += 4;
                }
                case 5, 6 -> {
                    utfOff[idx] = 0;
                    clsNameIdx[idx] = 0;
                    pos += 8;
                    idx++;
                }
                case 15 -> {
                    utfOff[idx] = 0;
                    clsNameIdx[idx] = 0;
                    pos += 3;
                }
                default -> {
                    return null;
                }
            }
        }
        if (pos + 6 > n) {
            return null;
        }
        int accessFlags = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
        boolean canBeSubclassed = (accessFlags & 0x0010) == 0; // !ACC_FINAL
        int thisIdx = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
        int superIdx = ((b[pos + 4] & 0xFF) << 8) | (b[pos + 5] & 0xFF);
        if (thisIdx <= 0 || thisIdx >= cpCount || superIdx <= 0 || superIdx >= cpCount) {
            return null;
        }
        int thisUtf = clsNameIdx[thisIdx];
        int superUtf = clsNameIdx[superIdx];
        if (thisUtf <= 0 || thisUtf >= cpCount || superUtf <= 0 || superUtf >= cpCount) {
            return null;
        }
        int tOff = utfOff[thisUtf], tLen = utfLen[thisUtf];
        if (tOff <= 0 || (tLen == EVENT_CLASS_BYTES.length && bytesEquals(b, tOff, EVENT_CLASS_BYTES))) {
            return null;
        }
        int sOff = utfOff[superUtf], sLen = utfLen[superUtf];
        if (sOff <= 0 || sLen <= 0) {
            return null;
        }
        if (sLen == EVENT_CLASS_BYTES.length && bytesEquals(b, sOff, EVENT_CLASS_BYTES)) {
            if (canBeSubclassed && tLen > 0 && NON_EVENT_SUPERS.size() < 16384) {
                NON_EVENT_SUPERS.putIfAbsent(new String(b, tOff, tLen, StandardCharsets.US_ASCII), Boolean.FALSE);
            }
            return null;
        }
        if (checkPrefixFast) {
            boolean safe = sLen == OBJECT_CLASS_BYTES.length && bytesEquals(b, sOff, OBJECT_CLASS_BYTES);
            if (!safe) {
                for (int i = 0; i < NON_EVENT_SUPER_PREFIXES.length; i++) {
                    byte[] prefix = NON_EVENT_SUPER_PREFIXES[i];
                    if (sLen >= prefix.length && bytesEquals(b, sOff, prefix)) {
                        safe = true;
                        break;
                    }
                }
            }
            if (safe) {
                if (canBeSubclassed && tLen > 0 && KNOWN_SUPER_BY_CLASS.size() < 65536) {
                    KNOWN_SUPER_BY_CLASS.putIfAbsent(new String(b, tOff, tLen, StandardCharsets.US_ASCII), "");
                }
                return "";
            }
        }
        String superStr = new String(b, sOff, sLen, StandardCharsets.US_ASCII);
        if (canBeSubclassed && tLen > 0 && KNOWN_SUPER_BY_CLASS.size() < 65536) {
            KNOWN_SUPER_BY_CLASS.putIfAbsent(new String(b, tOff, tLen, StandardCharsets.US_ASCII), superStr);
        }
        return superStr;
    }

    public static String[] fastSplitPathParts(String path) {
        if (path == null || path.isEmpty()) {
            return EMPTY_STRING_ARRAY;
        }
        int len = path.length();
        if (path.indexOf('\\') < 0) {
            int s0 = path.indexOf('/');
            if (s0 < 0) {
                return new String[] { path };
            }
            if (s0 > 0) {
                int s1 = path.indexOf('/', s0 + 1);
                if (s1 < 0) {
                    return s0 + 1 < len
                            ? new String[] { path.substring(0, s0), path.substring(s0 + 1) }
                            : new String[] { path.substring(0, s0) };
                }
                if (s1 > s0 + 1) {
                    int s2 = path.indexOf('/', s1 + 1);
                    if (s2 < 0) {
                        return s1 + 1 < len
                                ? new String[] { path.substring(0, s0), path.substring(s0 + 1, s1), path.substring(s1 + 1) }
                                : new String[] { path.substring(0, s0), path.substring(s0 + 1, s1) };
                    }
                }
            }
        }
        String norm = path.indexOf('\\') >= 0 ? path.replace('\\', '/') : path;
        len = norm.length();
        int count = 0;
        int i = 0;
        while (i < len) {
            int slash = norm.indexOf('/', i);
            if (slash < 0) {
                count++;
                break;
            }
            if (slash != i) {
                count++;
            }
            i = slash + 1;
        }
        if (count == 0) {
            return EMPTY_STRING_ARRAY;
        }
        String[] parts = new String[count];
        int out = 0;
        i = 0;
        while (i < len) {
            int slash = norm.indexOf('/', i);
            if (slash < 0) {
                parts[out++] = norm.substring(i);
                break;
            }
            if (slash != i) {
                parts[out++] = norm.substring(i, slash);
            }
            i = slash + 1;
        }
        return parts;
    }

    public static String fastUnionToString(boolean absolute, String[] parts) {
        if (parts == null || parts.length == 0) {
            return absolute ? "/" : "";
        }
        int n = parts.length;
        if (n == 1) {
            if (!absolute) {
                return parts[0];
            }
            if (parts[0].isEmpty()) {
                return "/";
            }
            return "/".concat(parts[0]);
        }
        int len = (absolute ? 1 : 0) + (n - 1);
        for (int i = 0; i < n; i++) {
            len += parts[i].length();
        }
        StringBuilder sb = new StringBuilder(len);
        if (absolute) {
            sb.append('/');
        }
        sb.append(parts[0]);
        for (int i = 1; i < n; i++) {
            sb.append('/').append(parts[i]);
        }
        return sb.toString();
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.jar.Attributes> JAR_MAIN_ATTRIBUTES =
            new java.util.concurrent.ConcurrentHashMap<>(512);
    private static final java.util.concurrent.atomic.AtomicBoolean BG_PREWARM_STARTED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final String[] BG_PREWARM_CLASSES = {
            "net.minecraft.world.level.block.state.BlockBehaviour",
            "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase",
            "net.minecraft.world.level.block.state.BlockState",
            "net.minecraft.world.level.block.state.StateDefinition",
            "net.minecraft.world.level.block.Block",
            "net.minecraft.world.level.block.Blocks",
            "net.minecraft.world.level.block.FireBlock",
            "net.minecraft.world.level.block.LiquidBlock",
            "net.minecraft.world.level.block.StairBlock",
            "net.minecraft.world.level.block.SlabBlock",
            "net.minecraft.world.level.block.FenceBlock",
            "net.minecraft.world.level.block.WallBlock",
            "net.minecraft.world.level.block.CrossCollisionBlock",
            "net.minecraft.world.level.block.LeavesBlock",
            "net.minecraft.world.level.block.piston.PistonHeadBlock",
            "net.minecraft.world.level.block.entity.BlockEntity",
            "net.minecraft.world.level.block.entity.BlockEntityType",
            "net.minecraft.world.item.Item",
            "net.minecraft.world.item.BlockItem",
            "net.minecraft.world.item.ItemStack",
            "net.minecraft.world.item.Items",
            "net.minecraft.world.item.TieredItem",
            "net.minecraft.world.item.ArmorItem",
            "net.minecraft.world.item.PotionItem",
            "net.minecraft.world.item.CreativeModeTab",
            "net.minecraft.world.item.CreativeModeTabs",
            "net.minecraft.world.item.alchemy.PotionBrewing",
            "net.minecraft.world.item.enchantment.Enchantment",
            "net.minecraft.world.item.enchantment.ProtectionEnchantment",
            "net.minecraft.world.item.enchantment.Enchantments",
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.world.entity.Mob",
            "net.minecraft.world.entity.PathfinderMob",
            "net.minecraft.world.entity.animal.Animal",
            "net.minecraft.world.entity.monster.Monster",
            "net.minecraft.world.entity.player.Player",
            "net.minecraft.world.entity.EntityType",
            "net.minecraft.world.entity.ai.attributes.Attributes",
            "net.minecraft.world.entity.ai.attributes.DefaultAttributes",
            "net.minecraft.core.registries.BuiltInRegistries",
            "net.minecraft.core.MappedRegistry",
            "net.minecraft.world.level.levelgen.feature.Feature",
            "net.minecraft.world.level.levelgen.feature.configurations.SpikeConfiguration",
            "net.minecraft.util.datafix.DataFixers",
            "net.minecraft.util.datafix.fixes.ChunkPalettedStorageFix",
            "net.minecraft.util.datafix.fixes.BlockStateData",
            "net.minecraft.util.datafix.schemas.V1460",
            "net.minecraft.util.datafix.schemas.V704",
            "net.minecraft.world.level.Level",
            "net.minecraft.server.level.ServerLevel",
            "net.minecraft.client.multiplayer.ClientLevel",
            "net.minecraft.client.Minecraft",
            "net.minecraft.client.Options",
            "net.minecraft.client.renderer.GameRenderer",
            "net.minecraft.client.renderer.LevelRenderer",
            "net.minecraft.client.renderer.RenderType",
            "net.minecraft.client.renderer.entity.EntityRenderDispatcher",
            "net.minecraft.client.renderer.entity.EntityRenderers",
            "net.minecraft.client.renderer.block.BlockRenderDispatcher",
            "net.minecraft.client.renderer.block.BlockModelShaper",
            "net.minecraft.client.renderer.entity.ItemRenderer",
            "net.minecraft.client.particle.ParticleEngine",
            "net.minecraft.client.gui.screens.Screen",
            "net.minecraft.client.gui.screens.TitleScreen",
            "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
            "net.minecraft.client.gui.Font",
            "net.minecraft.client.gui.font.FontManager",
            "net.minecraft.client.gui.font.FontSet",
            "net.minecraft.client.renderer.texture.TextureAtlas",
            "net.minecraft.client.renderer.texture.SpriteLoader",
            "net.minecraft.client.resources.model.ModelBakery",
            "net.minecraft.client.resources.model.ModelManager",
            "net.minecraft.client.renderer.ShaderInstance",
            "net.minecraft.client.player.LocalPlayer",
            "net.minecraft.client.player.AbstractClientPlayer",
            "net.minecraft.world.phys.shapes.Shapes",
            "net.minecraft.world.phys.shapes.VoxelShape",
            "net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape",
            "net.minecraft.world.phys.shapes.ArrayVoxelShape",
            "net.minecraft.resources.ResourceLocation",
            "net.minecraft.resources.FileToIdConverter",
            "net.minecraft.util.GsonHelper",
            "net.minecraft.FileUtil",
            "net.minecraft.core.IdMapper",
            "net.minecraft.world.level.block.state.StateHolder",
            "net.minecraft.client.renderer.texture.SpriteContents",
            "net.minecraft.client.renderer.texture.MipmapGenerator",
            "net.minecraft.client.renderer.texture.Stitcher",
            "net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader",
            "net.minecraft.client.renderer.texture.atlas.sources.DirectoryLister",
            "net.minecraft.client.resources.model.ModelResourceLocation",
            "net.minecraft.client.renderer.block.model.ItemModelGenerator",
            "net.minecraft.client.renderer.block.model.BlockModel",
            "net.minecraft.client.renderer.block.model.FaceBakery",
            "net.minecraft.client.gui.font.CodepointMap",
            "net.minecraft.client.gui.font.providers.UnihexProvider",
            "net.minecraft.client.sounds.SoundManager",
            "net.minecraft.client.sounds.SoundEngine",
            "net.minecraft.client.resources.language.ClientLanguage",
            "net.minecraft.server.packs.resources.FallbackResourceManager",
            "net.minecraft.server.packs.resources.MultiPackResourceManager",
            "net.minecraft.server.packs.resources.SimplePreparableReloadListener",
            "net.minecraft.server.packs.linkfs.LinkFSPath",
            "net.minecraftforge.resource.PathPackResources",
            "net.minecraftforge.resource.DelegatingPackResources",
            "net.minecraftforge.registries.ForgeRegistry",
            "net.minecraftforge.registries.GameData",
            "net.minecraftforge.registries.NamespacedWrapper",
            "net.minecraftforge.registries.NamespacedDefaultedWrapper",
            "net.minecraftforge.registries.RegistryObject",
            "net.minecraftforge.registries.ObjectHolderRegistry",
            "net.minecraftforge.registries.DeferredRegister",
            "net.minecraftforge.client.ForgeHooksClient",
            "com.mojang.blaze3d.platform.NativeImage",
            "com.mojang.blaze3d.vertex.PoseStack",
            "com.mojang.blaze3d.vertex.VertexBuffer",
            "net.minecraft.world.level.biome.Biome",
            "net.minecraft.world.level.biome.Biomes",
            "net.minecraft.world.level.levelgen.placement.PlacedFeature",
            "net.minecraft.world.level.levelgen.feature.ConfiguredFeature",
            "net.minecraft.world.level.levelgen.structure.Structure",
            "net.minecraft.world.level.levelgen.NoiseGeneratorSettings",
            "net.minecraft.world.level.levelgen.SurfaceRules",
            "net.minecraft.world.level.storage.loot.LootDataType",
            "net.minecraft.world.item.crafting.RecipeManager",
            "net.minecraft.sounds.SoundEvents",
            "net.minecraft.core.particles.ParticleTypes",
            "net.minecraft.world.effect.MobEffects",
            "net.minecraft.world.item.alchemy.Potions",
            "net.minecraft.client.renderer.RenderBuffers",
            "net.minecraft.client.renderer.MultiBufferSource",
            "net.minecraft.client.color.block.BlockColors",
            "net.minecraft.client.color.item.ItemColors",
            "net.minecraft.client.renderer.entity.LivingEntityRenderer",
            "net.minecraft.client.model.EntityModel",
            "net.minecraft.client.model.geom.ModelLayers",
            "net.minecraft.client.model.geom.EntityModelSet",
            "net.minecraft.client.renderer.FaceInfo",
            "net.minecraft.client.renderer.LightTexture",
            "net.minecraft.client.renderer.RenderStateShard",
            "net.minecraft.client.renderer.block.model.BlockElementRotation",
            "net.minecraft.client.renderer.block.model.ItemTransform",
            "net.minecraft.client.renderer.block.model.multipart.OrCondition",
            "net.minecraft.client.renderer.blockentity.BellRenderer",
            "net.minecraft.client.renderer.blockentity.ChestRenderer",
            "net.minecraft.client.renderer.blockentity.SignRenderer",
            "net.minecraft.client.renderer.entity.AllayRenderer",
            "net.minecraft.client.renderer.entity.CamelRenderer",
            "net.minecraft.client.renderer.entity.DisplayRenderer",
            "net.minecraft.client.renderer.entity.EndermiteRenderer",
            "net.minecraft.client.renderer.entity.FallingBlockRenderer",
            "net.minecraft.client.renderer.entity.GoatRenderer",
            "net.minecraft.client.renderer.entity.IronGolemRenderer",
            "net.minecraft.client.renderer.entity.LlamaSpitRenderer",
            "net.minecraft.client.renderer.entity.PandaRenderer",
            "net.minecraft.client.renderer.entity.RabbitRenderer",
            "net.minecraft.client.renderer.entity.SkeletonRenderer",
            "net.minecraft.client.renderer.entity.StriderRenderer",
            "net.minecraft.client.renderer.entity.TurtleRenderer",
            "net.minecraft.client.renderer.entity.WitherBossRenderer",
            "net.minecraft.client.renderer.item.CompassItemPropertyFunction",
            "net.minecraft.client.resources.model.ModelBaker",
            "net.minecraft.client.resources.model.WeightedBakedModel",
            "net.minecraft.client.gui.font.FontTexture",
            "net.minecraft.client.gui.font.providers.BitmapProvider",
            "net.minecraft.client.gui.screens.DirectJoinServerScreen",
            "net.minecraft.client.gui.screens.OptionsSubScreen",
            "net.minecraft.client.gui.screens.worldselection.SelectWorldScreen",
            "net.minecraft.util.datafix.fixes.AdvancementsRenameFix",
            "net.minecraft.util.datafix.fixes.BlockEntityBlockStateFix",
            "net.minecraft.util.datafix.fixes.BlockEntitySignTextStrictJsonFix",
            "net.minecraft.util.datafix.fixes.CauldronRenameFix",
            "net.minecraft.util.datafix.fixes.CriteriaRenameFix",
            "net.minecraft.util.datafix.fixes.EntityCodSalmonFix",
            "net.minecraft.util.datafix.fixes.EntityIdFix",
            "net.minecraft.util.datafix.fixes.EntityRavagerRenameFix",
            "net.minecraft.util.datafix.fixes.EntityTheRenameningFix",
            "net.minecraft.util.datafix.fixes.FeatureFlagRemoveFix",
            "net.minecraft.util.datafix.fixes.ItemShulkerBoxColorFix",
            "net.minecraft.util.datafix.fixes.ItemWaterPotionFix",
            "net.minecraft.util.datafix.fixes.LevelUUIDFix",
            "net.minecraft.util.datafix.fixes.ObjectiveDisplayNameFix",
            "net.minecraft.util.datafix.fixes.OptionsKeyLwjgl3Fix",
            "net.minecraft.util.datafix.fixes.PoiTypeRenameFix",
            "net.minecraft.util.datafix.fixes.RenamedCoralFix",
            "net.minecraft.util.datafix.fixes.StatsRenameFix",
            "net.minecraft.util.datafix.fixes.VillagerDataFix",
            "net.minecraft.util.datafix.fixes.WorldGenSettingsHeightAndBiomeFix",
            "net.minecraft.util.datafix.schemas.V106",
            "net.minecraft.util.datafix.schemas.V1451_3",
            "net.minecraft.util.datafix.schemas.V1483",
            "net.minecraft.util.datafix.schemas.V1920",
            "net.minecraft.util.datafix.schemas.V2509",
            "net.minecraft.util.datafix.schemas.V2688",
            "net.minecraft.util.datafix.schemas.V3081",
            "net.minecraft.util.datafix.schemas.V3327",
            "net.minecraft.util.datafix.schemas.V703",
            "net.minecraft.world.entity.AreaEffectCloud",
            "net.minecraft.world.entity.EquipmentSlot",
            "net.minecraft.world.entity.SpawnPlacements",
            "net.minecraft.world.entity.ai.attributes.AttributeInstance",
            "net.minecraft.world.entity.ambient.Bat",
            "net.minecraft.world.entity.animal.Cat",
            "net.minecraft.world.entity.animal.FrogVariant",
            "net.minecraft.world.entity.animal.Pufferfish",
            "net.minecraft.world.entity.animal.Turtle",
            "net.minecraft.world.level.block.ChestBlock",
            "net.minecraft.world.level.block.FurnaceBlock",
            "net.minecraft.world.level.block.DoorBlock",
            "net.minecraft.world.level.block.TrapDoorBlock",
            "net.minecraft.world.level.block.ButtonBlock",
            "net.minecraft.world.level.block.PressurePlateBlock",
            "net.minecraft.world.level.block.RedstoneWireBlock",
            "net.minecraft.world.level.block.RepeaterBlock",
            "net.minecraft.world.level.block.ComparatorBlock",
            "net.minecraft.world.level.block.PistonBaseBlock",
            "net.minecraft.world.level.block.HopperBlock",
            "net.minecraft.world.level.block.DispenserBlock",
            "net.minecraft.world.level.block.CropBlock",
            "net.minecraft.world.level.block.StemBlock",
            "net.minecraft.world.level.block.VineBlock",
            "net.minecraft.world.level.block.GlassBlock",
            "net.minecraft.world.level.block.IceBlock",
            "net.minecraft.world.level.block.SnowLayerBlock",
            "net.minecraft.world.level.block.WaterlilyBlock",
            "net.minecraft.world.level.block.BedBlock",
            "net.minecraft.world.level.block.SignBlock",
            "net.minecraft.world.level.block.BannerBlock",
            "net.minecraft.world.level.block.SkullBlock",
            "net.minecraft.world.level.block.AnvilBlock",
            "net.minecraft.world.level.block.BrewingStandBlock",
            "net.minecraft.world.level.block.EnchantmentTableBlock",
            "net.minecraft.world.level.block.BeaconBlock",
            "net.minecraft.world.level.block.SpawnerBlock",
            "net.minecraft.world.level.block.CauldronBlock",
            "net.minecraft.world.level.block.ComposterBlock",
            "net.minecraft.world.level.block.BarrelBlock",
            "net.minecraft.world.level.block.LanternBlock",
            "net.minecraft.world.level.block.CampfireBlock",
            "net.minecraft.world.level.block.BeehiveBlock",
            "net.minecraft.world.level.block.SculkSensorBlock",
            "net.minecraft.world.item.BowItem",
            "net.minecraft.world.item.CrossbowItem",
            "net.minecraft.world.item.TridentItem",
            "net.minecraft.world.item.FishingRodItem",
            "net.minecraft.world.item.ElytraItem",
            "net.minecraft.world.item.ShieldItem",
            "net.minecraft.world.item.MapItem",
            "net.minecraft.world.item.CompassItem",
            "net.minecraft.world.item.BookItem",
            "net.minecraft.world.item.WritableBookItem",
            "net.minecraft.world.item.EnchantedBookItem",
            "net.minecraft.world.item.BannerItem",
            "net.minecraft.world.item.DyeItem",
            "net.minecraft.world.item.SpawnEggItem",
            "net.minecraft.resources.ResourceKey", "net.minecraft.world.level.gameevent.GameEvent", "net.minecraftforge.registries.RegistryBuilder",
            "net.minecraft.world.level.block.state.properties.Property", "net.minecraft.core.BlockPos", "net.minecraft.world.level.block.state.properties.BooleanProperty",
            "net.minecraft.world.entity.item.ItemEntity", "net.minecraftforge.registries.RegistryManager", "net.minecraftforge.registries.ForgeRegistryTagManager",
            "net.minecraft.world.level.material.Fluid", "net.minecraft.world.effect.MobEffect", "net.minecraft.world.entity.ai.village.poi.PoiTypes",
            "net.minecraft.world.entity.ai.village.poi.PoiType", "net.minecraft.world.entity.ai.sensing.SensorType", "net.minecraft.world.entity.schedule.Activity",
            "net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecoratorType", "net.minecraft.server.level.ServerPlayer", "net.minecraft.core.Direction",
            "net.minecraft.world.phys.shapes.CubeVoxelShape", "net.minecraft.world.phys.shapes.SliceShape", "net.minecraft.world.phys.shapes.CubePointRange",
            "net.minecraft.world.level.block.state.properties.NoteBlockInstrument", "net.minecraftforge.network.simple.SimpleChannel", "net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket",
            "net.minecraft.network.FriendlyByteBuf", "net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket", "net.minecraft.network.protocol.login.ServerboundCustomQueryPacket",
            "net.minecraft.network.protocol.login.ClientboundCustomQueryPacket", "net.minecraftforge.network.HandshakeHandler", "net.minecraft.world.item.DyeColor",
            "net.minecraft.world.level.block.LayeredCauldronBlock", "net.minecraft.world.level.block.SpreadingSnowyDirtBlock", "net.minecraft.world.level.block.SnowyDirtBlock",
            "net.minecraft.world.level.block.grower.AbstractMegaTreeGrower", "net.minecraft.world.level.block.SandBlock", "net.minecraft.world.level.block.CherryLeavesBlock",
            "net.minecraft.world.level.block.SpongeBlock", "net.minecraft.world.level.block.NoteBlock", "net.minecraft.world.level.block.piston.PistonBaseBlock",
            "net.minecraft.world.level.block.DeadBushBlock", "net.minecraft.world.level.block.DoublePlantBlock", "net.minecraft.world.level.block.FlowerBlock",
            "net.minecraft.world.level.block.ChiseledBookShelfBlock", "net.minecraft.world.level.block.RedStoneWireBlock", "net.minecraft.world.level.block.FarmBlock",
            "net.minecraft.world.level.block.StandingSignBlock", "net.minecraft.world.level.block.LadderBlock", "net.minecraft.world.level.block.WallSignBlock",
            "net.minecraft.world.level.block.CeilingHangingSignBlock", "net.minecraft.world.level.block.WallHangingSignBlock", "net.minecraft.world.level.block.CactusBlock",
            "net.minecraft.world.level.block.SugarCaneBlock", "net.minecraft.world.level.block.JukeboxBlock", "net.minecraft.world.level.block.NetherPortalBlock",
            "net.minecraft.world.level.block.CarvedPumpkinBlock", "net.minecraft.world.level.block.CakeBlock", "net.minecraft.world.level.block.IronBarsBlock",
            "net.minecraft.world.level.block.FenceGateBlock", "net.minecraft.world.level.block.LavaCauldronBlock", "net.minecraft.world.level.block.TripWireHookBlock",
            "net.minecraft.world.level.block.FlowerPotBlock", "net.minecraft.world.level.block.WallSkullBlock", "net.minecraft.world.level.block.ObserverBlock",
            "net.minecraft.world.level.block.ShulkerBoxBlock", "net.minecraft.world.level.block.ConcretePowderBlock", "net.minecraft.world.level.block.GrowingPlantBodyBlock",
            "net.minecraft.world.level.block.BaseCoralPlantTypeBlock", "net.minecraft.world.level.block.BambooStalkBlock", "net.minecraft.world.level.block.BubbleColumnBlock",
            "net.minecraft.world.level.block.LecternBlock", "net.minecraft.world.level.block.SmithingTableBlock", "net.minecraft.world.level.block.BellBlock",
            "net.minecraft.world.level.block.SweetBerryBushBlock", "net.minecraft.world.level.block.AbstractCandleBlock", "net.minecraft.world.level.block.PowderSnowBlock",
            "net.minecraft.world.level.block.WeatheringCopper", "net.minecraft.world.level.block.LightningRodBlock", "net.minecraft.world.level.block.MossBlock",
            "net.minecraft.world.level.block.DecoratedPotBlock", "net.minecraft.world.level.storage.loot.BuiltInLootTables", "net.minecraft.world.level.material.FlowingFluid",
            "net.minecraft.world.level.material.FluidState", "net.minecraft.world.level.Explosion", "net.minecraft.world.item.context.BlockPlaceContext",
            "net.minecraft.world.item.context.UseOnContext", "net.minecraft.world.entity.item.FallingBlockEntity", "net.minecraft.world.level.block.entity.BrushableBlockEntity",
            "net.minecraft.world.Container", "net.minecraft.world.level.block.entity.DispenserBlockEntity", "net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity",
            "net.minecraft.world.level.block.entity.BaseContainerBlockEntity", "net.minecraft.world.level.block.entity.BedBlockEntity", "net.minecraft.world.entity.vehicle.AbstractMinecart",
            "net.minecraft.world.phys.AABB", "net.minecraft.world.level.block.piston.PistonStructureResolver", "net.minecraft.world.level.block.piston.PistonMovingBlockEntity",
            "net.minecraft.world.entity.ai.attributes.RangedAttribute", "net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer", "net.minecraft.world.entity.projectile.Projectile",
            "net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity", "net.minecraft.core.particles.SimpleParticleType", "net.minecraft.world.level.block.entity.SpawnerBlockEntity",
            "net.minecraft.world.level.block.entity.ChestBlockEntity", "net.minecraft.world.inventory.AbstractContainerMenu", "net.minecraft.world.inventory.CraftingMenu",
            "net.minecraft.world.entity.player.Inventory", "net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity", "net.minecraft.world.level.block.entity.SignBlockEntity",
            "net.minecraft.world.level.block.state.properties.BlockSetType", "net.minecraft.world.level.block.entity.HangingSignBlockEntity", "net.minecraft.world.entity.projectile.AbstractArrow",
            "net.minecraft.world.level.block.entity.JukeboxBlockEntity", "net.minecraft.world.entity.animal.SnowGolem", "net.minecraft.world.entity.animal.IronGolem",
            "net.minecraft.world.inventory.EnchantmentMenu", "net.minecraft.world.level.block.entity.BrewingStandBlockEntity", "net.minecraft.world.level.block.entity.EnderChestBlockEntity",
            "net.minecraft.world.inventory.PlayerEnderChestContainer", "net.minecraft.world.SimpleContainer", "net.minecraft.world.level.block.entity.BeaconBlockEntity",
            "net.minecraft.world.level.block.entity.SkullBlockEntity", "net.minecraft.world.entity.boss.wither.WitherBoss", "net.minecraft.world.inventory.AnvilMenu",
            "net.minecraft.world.inventory.ItemCombinerMenu", "net.minecraft.world.damagesource.DamageSource", "net.minecraft.world.entity.decoration.ItemFrame",
            "net.minecraft.world.entity.decoration.HangingEntity", "net.minecraft.world.level.block.entity.HopperBlockEntity", "net.minecraft.world.level.block.entity.BannerBlockEntity",
            "net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity", "net.minecraft.world.level.block.entity.ConduitBlockEntity", "net.minecraft.world.inventory.LoomMenu",
            "net.minecraft.world.level.block.entity.BarrelBlockEntity", "net.minecraft.world.inventory.CartographyTableMenu", "net.minecraft.world.inventory.GrindstoneMenu",
            "net.minecraft.world.level.block.entity.LecternBlockEntity", "net.minecraft.world.inventory.SmithingMenu", "net.minecraft.world.level.block.entity.BellBlockEntity",
            "net.minecraft.world.level.block.entity.CampfireBlockEntity", "net.minecraft.world.level.chunk.ChunkGenerator", "net.minecraft.world.level.block.entity.BeehiveBlockEntity",
            "net.minecraft.world.level.ExplosionDamageCalculator", "net.minecraft.world.entity.LightningBolt", "net.minecraft.world.entity.animal.frog.Tadpole",
            "net.minecraft.world.level.block.entity.DecoratedPotBlockEntity", "net.minecraft.world.item.SwordItem", "net.minecraft.world.item.ShovelItem",
            "net.minecraft.world.item.DiggerItem", "net.minecraft.world.item.AxeItem", "net.minecraft.world.item.HoeItem",
            "net.minecraft.world.item.BowlFoodItem", "net.minecraft.world.item.HangingEntityItem", "net.minecraft.world.item.BucketItem",
            "net.minecraft.world.item.SolidBucketItem", "net.minecraft.world.item.MobBucketItem", "net.minecraft.world.item.BundleItem",
            "net.minecraft.world.item.InkSacItem", "net.minecraft.world.item.BoneMealItem", "net.minecraft.world.item.ShearsItem",
            "net.minecraft.world.item.WrittenBookItem", "net.minecraft.world.item.FireworkRocketItem", "net.minecraft.world.item.LeadItem",
            "net.minecraft.world.item.NameTagItem", "net.minecraft.world.item.RecordItem", "net.minecraft.world.item.SuspiciousStewItem",
            "net.minecraft.world.item.BannerPatternItem", "net.minecraft.world.item.HoneycombItem", "net.minecraft.world.item.BrushItem",
            "net.minecraft.world.entity.animal.allay.Allay", "net.minecraft.world.level.chunk.ChunkAccess", "net.minecraft.world.level.chunk.LevelChunk",
            "net.minecraft.world.entity.animal.Chicken", "net.minecraft.world.entity.ai.navigation.PathNavigation", "net.minecraft.world.entity.ai.navigation.GroundPathNavigation",
            "net.minecraft.world.entity.decoration.LeashFenceKnotEntity", "net.minecraft.world.entity.MobCategory", "net.minecraft.world.entity.decoration.ArmorStand",
            "net.minecraft.world.entity.projectile.Arrow", "net.minecraft.world.entity.animal.axolotl.Axolotl", "net.minecraft.world.entity.ExperienceOrb",
            "net.minecraft.world.entity.animal.Bee", "net.minecraft.world.entity.ai.goal.BreedGoal", "net.minecraft.world.entity.ai.goal.TemptGoal",
            "net.minecraft.world.entity.ai.goal.FollowParentGoal", "net.minecraft.world.entity.ai.goal.FloatGoal", "net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal",
            "net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal", "net.minecraft.world.entity.monster.Blaze", "net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal",
            "net.minecraft.world.entity.ai.goal.RandomStrollGoal", "net.minecraft.world.entity.Display", "net.minecraft.world.entity.vehicle.Boat",
            "net.minecraft.world.entity.animal.camel.Camel", "net.minecraft.world.entity.animal.horse.AbstractHorse", "net.minecraft.world.entity.ai.goal.PanicGoal",
            "net.minecraft.world.entity.ai.goal.FollowOwnerGoal", "net.minecraft.world.entity.ai.goal.CatSitOnBlockGoal", "net.minecraft.world.entity.monster.Spider",
            "net.minecraft.world.entity.monster.Skeleton", "net.minecraft.world.entity.monster.AbstractSkeleton", "net.minecraft.world.entity.ai.goal.FollowFlockLeaderGoal",
            "net.minecraft.world.entity.animal.Cow", "net.minecraft.world.entity.monster.Creeper", "net.minecraft.world.entity.animal.horse.AbstractChestedHorse",
            "net.minecraft.world.entity.projectile.DragonFireball", "net.minecraft.world.entity.projectile.AbstractHurtingProjectile", "net.minecraft.world.entity.monster.Drowned",
            "net.minecraft.world.entity.monster.Zombie", "net.minecraft.world.entity.ai.goal.RemoveBlockGoal", "net.minecraft.world.entity.ai.goal.DoorInteractGoal",
            "net.minecraft.world.entity.projectile.ThrownEgg", "net.minecraft.world.entity.projectile.ThrowableItemProjectile", "net.minecraft.world.entity.projectile.ThrowableProjectile",
            "net.minecraft.world.entity.monster.EnderMan", "net.minecraft.world.entity.monster.Evoker", "net.minecraft.world.entity.monster.AbstractIllager",
            "net.minecraft.world.entity.raid.Raider", "net.minecraft.world.entity.projectile.FireworkRocketEntity", "net.minecraft.world.entity.animal.Fox",
            "net.minecraft.world.entity.animal.frog.Frog", "net.minecraft.world.entity.GlowSquid", "net.minecraft.world.entity.animal.Squid",
            "net.minecraft.world.entity.animal.goat.Goat", "net.minecraft.world.entity.animal.horse.Horse", "net.minecraft.world.entity.ai.goal.GolemRandomStrollInVillageGoal",
            "net.minecraft.world.entity.projectile.LargeFireball", "net.minecraft.world.entity.projectile.Fireball", "net.minecraft.world.entity.animal.horse.Llama",
            "net.minecraft.world.entity.ai.goal.LlamaFollowCaravanGoal", "net.minecraft.world.entity.monster.Slime", "net.minecraft.world.entity.animal.MushroomCow",
            "net.minecraft.world.entity.animal.Ocelot", "net.minecraft.world.entity.decoration.Painting", "net.minecraft.world.entity.animal.Parrot",
            "net.minecraft.world.entity.monster.Phantom", "net.minecraft.world.entity.animal.Pig", "net.minecraft.world.entity.monster.ZombifiedPiglin",
            "net.minecraft.world.entity.monster.piglin.Piglin", "net.minecraft.world.entity.monster.Pillager", "net.minecraft.world.entity.animal.PolarBear",
            "net.minecraft.world.entity.projectile.ThrownPotion", "net.minecraft.world.entity.animal.Rabbit", "net.minecraft.world.entity.animal.Sheep",
            "net.minecraft.world.inventory.CraftingContainer", "net.minecraft.world.entity.animal.horse.SkeletonHorse", "net.minecraft.world.entity.projectile.SmallFireball",
            "net.minecraft.world.entity.projectile.Snowball", "net.minecraft.world.level.BaseSpawner", "net.minecraft.world.entity.projectile.SpectralArrow",
            "net.minecraft.world.entity.monster.Stray", "net.minecraft.network.syncher.EntityDataSerializers", "net.minecraft.world.entity.Pose",
            "net.minecraft.network.syncher.SynchedEntityData", "net.minecraft.world.entity.ai.sensing.SecondaryPoiSensor", "net.minecraft.world.entity.ai.sensing.PiglinSpecificSensor",
            "net.minecraft.world.entity.ai.sensing.AxolotlAttackablesSensor", "net.minecraft.world.entity.ai.sensing.TemptingSensor", "net.minecraft.world.entity.ai.sensing.FrogAttackablesSensor",
            "net.minecraft.world.level.border.WorldBorder", "net.minecraft.world.entity.monster.Vex", "net.minecraft.world.entity.npc.Villager",
            "net.minecraft.world.entity.npc.AbstractVillager", "net.minecraft.world.inventory.MerchantMenu", "net.minecraft.world.entity.monster.Witch",
            "net.minecraft.world.entity.npc.WanderingTrader", "net.minecraft.world.entity.monster.WitherSkeleton", "net.minecraft.world.entity.animal.Wolf",
            "net.minecraft.world.entity.projectile.FishingHook", "net.minecraft.world.entity.EntitySelector", "net.minecraft.world.item.crafting.Ingredient",
            "net.minecraft.world.effect.MobEffectInstance", "net.minecraft.server.network.ServerGamePacketListenerImpl", "net.minecraft.nbt.CompoundTag",
            "net.minecraft.nbt.NumericTag", "net.minecraft.core.dispenser.AbstractProjectileDispenseBehavior", "net.minecraft.world.item.enchantment.OxygenEnchantment",
            "net.minecraft.world.item.enchantment.ThornsEnchantment", "net.minecraft.world.item.enchantment.FrostWalkerEnchantment", "net.minecraft.world.item.enchantment.SoulSpeedEnchantment",
            "net.minecraft.world.item.enchantment.SwiftSneakEnchantment", "net.minecraft.world.item.enchantment.DamageEnchantment", "net.minecraft.world.item.enchantment.KnockbackEnchantment",
            "net.minecraft.world.item.enchantment.FireAspectEnchantment", "net.minecraft.world.item.enchantment.LootBonusEnchantment", "net.minecraft.world.item.enchantment.SweepingEdgeEnchantment",
            "net.minecraft.world.item.enchantment.DiggingEnchantment", "net.minecraft.world.item.enchantment.DigDurabilityEnchantment", "net.minecraft.world.item.enchantment.ArrowDamageEnchantment",
            "net.minecraft.world.item.enchantment.ArrowKnockbackEnchantment", "net.minecraft.world.item.enchantment.FishingSpeedEnchantment", "net.minecraft.world.item.enchantment.TridentLoyaltyEnchantment",
            "net.minecraft.world.item.enchantment.TridentImpalerEnchantment", "net.minecraft.world.item.enchantment.TridentRiptideEnchantment", "net.minecraft.world.item.enchantment.ArrowPiercingEnchantment",
            "net.minecraft.world.item.enchantment.MendingEnchantment", "net.minecraftforge.items.IItemHandler", "net.minecraft.world.inventory.AbstractFurnaceMenu",
            "net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor", "net.minecraft.stats.Stats", "net.minecraft.world.level.levelgen.Heightmap",
            "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager", "net.minecraft.world.inventory.Slot", "net.minecraft.world.inventory.FurnaceResultSlot",
            "net.minecraft.world.item.crafting.Recipe", "net.minecraft.world.inventory.ResultSlot", "net.minecraft.world.item.crafting.ArmorDyeRecipe",
            "net.minecraft.world.item.crafting.BookCloningRecipe", "net.minecraft.world.item.crafting.MapExtendingRecipe", "net.minecraft.world.item.crafting.ShapedRecipe",
            "net.minecraft.world.item.crafting.BannerDuplicateRecipe", "net.minecraft.world.level.storage.loot.predicates.LootItemCondition", "net.minecraft.world.level.storage.loot.functions.EnchantRandomlyFunction",
            "net.minecraft.world.level.storage.loot.functions.LootingEnchantFunction", "net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction", "net.minecraft.world.level.storage.loot.functions.ApplyBonusCount",
            "net.minecraft.util.random.WeightedRandomList", "net.minecraft.world.level.dimension.DimensionType", "net.minecraft.world.level.levelgen.feature.TreeFeature",
            "net.minecraft.world.level.levelgen.feature.SpringFeature", "net.minecraft.world.level.levelgen.feature.HugeRedMushroomFeature", "net.minecraft.world.level.levelgen.feature.HugeBrownMushroomFeature",
            "net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature", "net.minecraft.world.level.levelgen.feature.MultifaceGrowthFeature", "net.minecraft.world.level.levelgen.feature.LakeFeature",
            "net.minecraft.world.level.levelgen.feature.SimpleBlockFeature", "net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList", "net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement",
            "net.minecraft.world.level.levelgen.structure.placement.StructurePlacement", "net.minecraft.world.level.levelgen.structure.StructureSet", "net.minecraft.world.level.levelgen.structure.StructurePiece",
            "net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece", "net.minecraft.world.level.biome.MobSpawnSettings", "net.minecraft.world.level.levelgen.structure.structures.JigsawStructure",
            "net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool", "net.minecraft.world.level.levelgen.placement.PlacementContext", "net.minecraft.world.level.biome.MobSpawnSettings$Builder",
            "net.minecraft.world.level.biome.Climate$ParameterPoint", "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", "net.minecraft.world.level.levelgen.DebugLevelSource",
            "net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement", "net.minecraft.world.level.levelgen.structure.pools.ListPoolElement", "net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement",
            "net.minecraftforge.network.NetworkHooks"
    };

    private static String normalizeJarAttrKey(Path jarPath) {
        return jarPath.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    private static void recordJarManifest(Path jarPath, Manifest mf) {
        if (jarPath == null || mf == null || JAR_MAIN_ATTRIBUTES.size() >= 1024) {
            return;
        }
        JAR_MAIN_ATTRIBUTES.putIfAbsent(normalizeJarAttrKey(jarPath), mf.getMainAttributes());
    }

    public static java.util.jar.Attributes getCachedJarAttributes(java.io.File file) {
        if (file == null) {
            return null;
        }
        String key = file.getPath().toLowerCase(Locale.ROOT);
        java.util.jar.Attributes direct = JAR_MAIN_ATTRIBUTES.get(key);
        if (direct != null) {
            return direct;
        }
        try {
            return JAR_MAIN_ATTRIBUTES.get(normalizeJarAttrKey(file.toPath()));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static InputStream ensureBuffered(InputStream in) {
        if (in == null || in instanceof java.io.BufferedInputStream || in instanceof java.io.ByteArrayInputStream) {
            return in;
        }
        return new java.io.BufferedInputStream(in, 65536);
    }

    public static void prewarmDuringBackgroundWait(Runnable transition) {
        if (!BG_PREWARM_STARTED.compareAndSet(false, true)) {
            return;
        }
        ClassLoader cl = transition != null ? transition.getClass().getClassLoader() : null;
        if (cl == null) {
            cl = Thread.currentThread().getContextClassLoader();
        }
        if (cl == null) {
            return;
        }
        final ClassLoader targetCl = cl;
        final boolean isClientDist = detectClientDist(targetCl);
        final java.util.concurrent.atomic.AtomicInteger nextIdx = new java.util.concurrent.atomic.AtomicInteger();
        int workers = 2;
        for (int w = 0; w < workers; w++) {
            final boolean warmOshi = (w == 0) && isClientDist;
            Thread t = new Thread(() -> {
                if (warmOshi) {
                    try {
                        Class<?> si = Class.forName("oshi.SystemInfo", true, targetCl);
                        Object info = si.getConstructor().newInstance();
                        Object hw = si.getMethod("getHardware").invoke(info);
                        hw.getClass().getMethod("getProcessor").invoke(hw);
                    } catch (Throwable ignored) {
                    }
                }
                while (true) {
                    int idx = nextIdx.getAndIncrement();
                    if (idx >= BG_PREWARM_CLASSES.length) {
                        break;
                    }
                    String clsName = BG_PREWARM_CLASSES[idx];
                    if (isClientDist ? !shouldBgPrewarmOnClient(clsName) : !shouldBgPrewarmOnServer(clsName)) {
                        continue;
                    }
                    try {
                        Class.forName(clsName, false, targetCl);
                    } catch (Throwable ignored) {
                    }
                }
            }, "Sunlit-BgPrewarm-" + w);
            t.setDaemon(true);
            t.start();
        }
    }

    private static boolean detectClientDist(ClassLoader targetCl) {
        try {
            Class<?> fmlEnv = Class.forName("net.minecraftforge.fml.loading.FMLEnvironment", false, targetCl);
            Object dist = fmlEnv.getField("dist").get(null);
            if (dist != null) {
                return "CLIENT".equals(String.valueOf(dist));
            }
        } catch (Throwable ignored) {
        }
        return !java.awt.GraphicsEnvironment.isHeadless() && targetCl.getResource("net/minecraft/client/main/Main.class") != null;
    }

    private static boolean shouldBgPrewarmOnServer(String name) {
        if (name == null) {
            return false;
        }
        return !name.startsWith("net.minecraft.client.")
                && !name.startsWith("com.mojang.blaze3d.")
                && !name.startsWith("org.lwjgl.");
    }

    private static boolean shouldBgPrewarmOnClient(String name) {
        if (name == null) {
            return false;
        }
        return !name.startsWith("net.minecraft.util.datafix.")
                && !name.startsWith("net.minecraft.server.level.")
                && !name.startsWith("net.minecraft.server.packs.resources.ReloadableServer")
                && !name.startsWith("net.minecraft.world.level.levelgen.")
                && !name.startsWith("net.minecraft.world.level.biome.")
                && !name.startsWith("net.minecraft.client.gui.screens.worldselection.")
                && !name.startsWith("net.minecraft.client.gui.screens.multiplayer.")
                && !name.startsWith("net.minecraft.client.gui.screens.advancements.")
                && !name.startsWith("net.minecraft.client.gui.screens.inventory.");
    }

    private static boolean bytesEquals(byte[] b, int off, byte[] pat) {
        for (int i = 0; i < pat.length; i++) {
            if (b[off + i] != pat[i]) {
                return false;
            }
        }
        return true;
    }

    public static boolean canSkipDistCleanerOnlyFast(Map plugins, byte[] bytes, ClassLoader loader) {
        if (plugins == null || plugins.isEmpty() || bytes == null || bytes.length < 16) {
            return false;
        }
        boolean needDist = false;
        boolean needEvent = false;
        boolean needEnum = false;
        boolean needObj = false;
        boolean needCap = false;
        for (Object entryObj : plugins.entrySet()) {
            Map.Entry e = (Map.Entry) entryObj;
            List list = (List) e.getValue();
            if (list == null || list.isEmpty()) {
                continue;
            }
            Object phase = e.getKey();
            boolean isAfter = (phase instanceof Enum) && "AFTER".equals(((Enum) phase).name());
            for (int i = 0, n = list.size(); i < n; i++) {
                Object p = list.get(i);
                if (p == null) {
                    return false;
                }
                if (p == distCleanerPlugin) {
                    needDist = true;
                } else if (p == eventBusPlugin) {
                    if (!isAfter) return false;
                    needEvent = true;
                } else if (p == enumExtenderPlugin) {
                    if (!isAfter) return false;
                    needEnum = true;
                } else if (p == objectHolderPlugin) {
                    if (!isAfter) return false;
                    needObj = true;
                } else if (p == capabilityTokenPlugin) {
                    if (!isAfter) return false;
                    needCap = true;
                } else {
                    String name = p.getClass().getName();
                    if ("net.minecraftforge.fml.loading.RuntimeDistCleaner".equals(name)) {
                        distCleanerPlugin = p;
                        needDist = true;
                    } else if ("net.minecraftforge.eventbus.service.ModLauncherService".equals(name)) {
                        eventBusPlugin = p;
                        if (!isAfter) return false;
                        needEvent = true;
                    } else if ("net.minecraftforge.fml.common.asm.RuntimeEnumExtender".equals(name)) {
                        enumExtenderPlugin = p;
                        if (!isAfter) return false;
                        needEnum = true;
                    } else if ("net.minecraftforge.fml.common.asm.ObjectHolderDefinalize".equals(name)) {
                        objectHolderPlugin = p;
                        if (!isAfter) return false;
                        needObj = true;
                    } else if ("net.minecraftforge.fml.common.asm.CapabilityTokenSubclass".equals(name)) {
                        capabilityTokenPlugin = p;
                        if (!isAfter) return false;
                        needCap = true;
                    } else {
                        return false;
                    }
                }
            }
        }
        int cpCount = ((bytes[8] & 0xFF) << 8) | (bytes[9] & 0xFF);
        if (cpCount <= 1) {
            return false;
        }
        CpScratch sc = needEvent ? CP_SCRATCH.get() : null;
        if (sc != null) {
            sc.ensure(cpCount);
        }
        int pos = 10;
        int len = bytes.length;
        int minLen = needEnum ? EXT_ENUM_NEEDLE.length
                : (needDist ? ONLYIN_NEEDLE.length
                : (needObj ? OBJ_HOLDER_NEEDLE.length
                : (needEvent ? SUBSCRIBE_NEEDLE.length
                : (needCap ? CAP_TOKEN_NEEDLE.length : Integer.MAX_VALUE))));
        for (int idx = 1; idx < cpCount; idx++) {
            if (pos >= len) {
                return false;
            }
            int tag = bytes[pos++] & 0xFF;
            switch (tag) {
                case 1 -> {
                    if (pos + 2 > len) return false;
                    int ulen = ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
                    pos += 2;
                    if (pos + ulen > len) return false;
                    if (sc != null) {
                        sc.utfOff[idx] = pos;
                        sc.utfLen[idx] = ulen;
                    }
                    if (ulen >= minLen && containsForgeAnnotationNeedle(
                            bytes, pos, ulen, needDist, needEvent, needEnum, needObj, needCap)) {
                        return false;
                    }
                    pos += ulen;
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> pos += 4;
                case 5, 6 -> {
                    pos += 8;
                    idx++;
                }
                case 7 -> {
                    if (sc != null && pos + 2 <= len) {
                        sc.clsNameIdx[idx] = ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
                    }
                    pos += 2;
                }
                case 8, 16, 19, 20 -> pos += 2;
                case 15 -> pos += 3;
                default -> {
                    return false;
                }
            }
        }
        if (!needEvent) {
            return true;
        }
        if (pos + 6 > len) {
            return false;
        }
        int superIdx = ((bytes[pos + 4] & 0xFF) << 8) | (bytes[pos + 5] & 0xFF);
        if (superIdx == 0) {
            return true;
        }
        if (superIdx >= cpCount) {
            return false;
        }
        int scUtf = sc.clsNameIdx[superIdx];
        if (scUtf <= 0 || scUtf >= cpCount) {
            return false;
        }
        int sOff = sc.utfOff[scUtf];
        int sLen = sc.utfLen[scUtf];
        if (sOff <= 0 || sLen <= 0 || sOff + sLen > len) {
            return false;
        }
        for (int p = 0; p < NON_EVENT_SUPER_PREFIXES.length; p++) {
            byte[] pfx = NON_EVENT_SUPER_PREFIXES[p];
            if (sLen >= pfx.length && bytesEquals(bytes, sOff, pfx)) {
                return true;
            }
        }
        String superName = new String(bytes, sOff, sLen, StandardCharsets.UTF_8);
        Boolean knownNonEvent = NON_EVENT_SUPERS.get(superName);
        if (knownNonEvent != null) {
            return knownNonEvent.booleanValue();
        }
        int thisIdx = ((bytes[pos + 2] & 0xFF) << 8) | (bytes[pos + 3] & 0xFF);
        if (thisIdx > 0 && thisIdx < cpCount) {
            int tcUtf = sc.clsNameIdx[thisIdx];
            if (tcUtf > 0 && tcUtf < cpCount && sc.utfLen[tcUtf] > 0) {
                String thisName = new String(bytes, sc.utfOff[tcUtf], sc.utfLen[tcUtf], StandardCharsets.UTF_8);
                KNOWN_SUPER_BY_CLASS.putIfAbsent(thisName, superName);
            }
        }
        return isKnownNonEventClass(bytes, loader);
    }

    private static boolean containsForgeAnnotationNeedle(
            byte[] buf, int off, int len,
            boolean needDist, boolean needEvent, boolean needEnum, boolean needObj, boolean needCap) {
        // Every Forge annotation/interface needle shares the 19-byte prefix "net/minecraftforge/".
        // Probe 'f' at +13 and 'n' at +0 in one scan before comparing prefix/suffix bytes.
        int minNeedle = needEnum ? EXT_ENUM_NEEDLE.length
                : (needDist ? ONLYIN_NEEDLE.length
                : (needObj ? OBJ_HOLDER_NEEDLE.length
                : (needEvent ? SUBSCRIBE_NEEDLE.length : CAP_TOKEN_NEEDLE.length)));
        int limit = off + len - minNeedle;
        int end = off + len;
        for (int i = off; i <= limit; i++) {
            if (buf[i] != 'n' || buf[i + 13] != 'f') {
                continue;
            }
            if (buf[i + 1] != 'e' || buf[i + 2] != 't' || buf[i + 3] != '/'
                    || buf[i + 14] != 'o' || buf[i + 15] != 'r' || buf[i + 16] != 'g'
                    || buf[i + 17] != 'e' || buf[i + 18] != '/'
                    || buf[i + 4] != 'm' || buf[i + 5] != 'i' || buf[i + 6] != 'n'
                    || buf[i + 7] != 'e' || buf[i + 8] != 'c' || buf[i + 9] != 'r'
                    || buf[i + 10] != 'a' || buf[i + 11] != 'f' || buf[i + 12] != 't') {
                continue;
            }
            int sfxOff = i + 19;
            int rem = end - sfxOff;
            switch (buf[sfxOff]) {
                case 'a':
                    if (needDist && rem >= FORGE_ONLYIN_SUFFIX.length
                            && bytesEquals(buf, sfxOff, FORGE_ONLYIN_SUFFIX)) {
                        return true;
                    }
                    break;
                case 'e':
                    if (needEvent && rem >= FORGE_SUBSCRIBE_SUFFIX.length
                            && bytesEquals(buf, sfxOff, FORGE_SUBSCRIBE_SUFFIX)) {
                        return true;
                    }
                    break;
                case 'c':
                    if (needEnum && rem >= FORGE_EXT_ENUM_SUFFIX.length
                            && bytesEquals(buf, sfxOff, FORGE_EXT_ENUM_SUFFIX)) {
                        return true;
                    }
                    if (needCap && rem >= FORGE_CAP_TOKEN_SUFFIX.length
                            && bytesEquals(buf, sfxOff, FORGE_CAP_TOKEN_SUFFIX)) {
                        return true;
                    }
                    break;
                case 'r':
                    if (needObj && rem >= FORGE_OBJ_HOLDER_SUFFIX.length
                            && bytesEquals(buf, sfxOff, FORGE_OBJ_HOLDER_SUFFIX)) {
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    public static String[] fastPathFromLocation(String packDir, String namespace, String path) {
        int s0 = path.indexOf('/');
        if (s0 < 0) {
            return new String[] { packDir, namespace, path };
        }
        int s1 = path.indexOf('/', s0 + 1);
        if (s1 < 0) {
            return new String[] {
                    packDir, namespace, path.substring(0, s0), path.substring(s0 + 1)
            };
        }
        int s2 = path.indexOf('/', s1 + 1);
        if (s2 < 0) {
            return new String[] {
                    packDir, namespace,
                    path.substring(0, s0), path.substring(s0 + 1, s1), path.substring(s1 + 1)
            };
        }
        int s3 = path.indexOf('/', s2 + 1);
        if (s3 < 0) {
            return new String[] {
                    packDir, namespace,
                    path.substring(0, s0), path.substring(s0 + 1, s1),
                    path.substring(s1 + 1, s2), path.substring(s2 + 1)
            };
        }
        int segs = 5;
        int scan = s3 + 1;
        while ((scan = path.indexOf('/', scan)) >= 0) {
            segs++;
            scan++;
        }
        String[] res = new String[segs + 2];
        res[0] = packDir;
        res[1] = namespace;
        res[2] = path.substring(0, s0);
        res[3] = path.substring(s0 + 1, s1);
        res[4] = path.substring(s1 + 1, s2);
        res[5] = path.substring(s2 + 1, s3);
        int idx = 6;
        int start = s3 + 1;
        while (true) {
            int next = path.indexOf('/', start);
            if (next < 0) {
                res[idx] = path.substring(start);
                return res;
            }
            res[idx++] = path.substring(start, next);
            start = next + 1;
        }
    }

    public static String fastMixinFormat(String fmt, Object... args) {
        if (fmt == null) {
            return "null";
        }
        if (args == null || args.length == 0) {
            return fmt.indexOf('%') < 0 ? fmt : String.format(fmt, args);
        }
        if (args.length == 1 && "%03x".equals(fmt) && args[0] instanceof Number) {
            int val = ((Number) args[0]).intValue();
            if (val >= 0 && val <= 0xFFF) {
                char[] buf = new char[3];
                buf[0] = HEX_CHARS[(val >>> 8) & 0xF];
                buf[1] = HEX_CHARS[(val >>> 4) & 0xF];
                buf[2] = HEX_CHARS[val & 0xF];
                return new String(buf);
            }
            return Integer.toHexString(val);
        }
        if (args.length == 2) {
            if ("%s%s".equals(fmt)) {
                return String.valueOf(args[0]).concat(String.valueOf(args[1]));
            }
            if ("%s::%s".equals(fmt)) {
                return String.valueOf(args[0]) + "::" + String.valueOf(args[1]);
            }
        }
        if (args.length == 3) {
            if ("md%s$%s$%s".equals(fmt)) {
                return "md" + args[0] + '$' + args[1] + '$' + args[2];
            }
            if ("fd%s$%s$%s".equals(fmt)) {
                return "fd" + args[0] + '$' + args[1] + '$' + args[2];
            }
        }
        if (args.length == 4 && "%s$%s%s$%s".equals(fmt)) {
            return String.valueOf(args[0]) + '$' + args[1] + args[2] + '$' + args[3];
        }
        int firstPct = fmt.indexOf('%');
        if (firstPct < 0) {
            return fmt;
        }
        int len = fmt.length();
        StringBuilder sb = new StringBuilder(len + args.length * 16);
        int last = 0;
        int argIdx = 0;
        int pct = firstPct;
        while (pct >= 0) {
            if (pct + 1 >= len || fmt.charAt(pct + 1) != 's' || argIdx >= args.length) {
                return String.format(fmt, args);
            }
            if (pct > last) {
                sb.append(fmt, last, pct);
            }
            sb.append(args[argIdx++]);
            last = pct + 2;
            pct = fmt.indexOf('%', last);
        }
        if (last < len) {
            sb.append(fmt, last, len);
        }
        return sb.toString();
    }

    private static final ClassValue<String> ASM_DESC_CACHE = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            StringBuilder sb = new StringBuilder(64);
            Class<?> cur = type;
            while (cur.isArray()) {
                sb.append('[');
                cur = cur.getComponentType();
            }
            if (cur.isPrimitive()) {
                if (cur == Integer.TYPE) sb.append('I');
                else if (cur == Void.TYPE) sb.append('V');
                else if (cur == Boolean.TYPE) sb.append('Z');
                else if (cur == Byte.TYPE) sb.append('B');
                else if (cur == Character.TYPE) sb.append('C');
                else if (cur == Short.TYPE) sb.append('S');
                else if (cur == Double.TYPE) sb.append('D');
                else if (cur == Float.TYPE) sb.append('F');
                else sb.append('J');
            } else {
                sb.append('L').append(cur.getName().replace('.', '/')).append(';');
            }
            return sb.toString();
        }
    };

    private static final ClassValue<String> ASM_INTERNAL_CACHE = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            return type.getName().replace('.', '/');
        }
    };

    public static String fastAsmDescriptor(Class<?> clazz) {
        return ASM_DESC_CACHE.get(clazz);
    }

    public static String fastAsmInternalName(Class<?> clazz) {
        return ASM_INTERNAL_CACHE.get(clazz);
    }

    private static final ConcurrentHashMap<String, byte[]> MOD_ICON_BYTES_CACHE = new ConcurrentHashMap<>(128);

    public static byte[] fastReadModIconBytes(Path path) throws IOException {
        if (path == null) {
            return Files.readAllBytes(null);
        }
        String key = path.toString();
        byte[] cached = MOD_ICON_BYTES_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        byte[] loaded = Files.readAllBytes(path);
        byte[] prev = MOD_ICON_BYTES_CACHE.putIfAbsent(key, loaded);
        return prev != null ? prev : loaded;
    }

    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    private static final class ExactByteArrayInputStream extends java.io.ByteArrayInputStream {
        ExactByteArrayInputStream(byte[] buf) {
            super(buf);
        }

        byte[] rawBytes() {
            return this.buf;
        }
    }

    public static InputStream wrapExactBytes(byte[] bytes) {
        return new ExactByteArrayInputStream(bytes);
    }

    private static volatile Map<ModuleReference, ConcurrentHashMap<String, byte[]>> prewarmedMixinBytes = Map.of();
    private static final boolean TRACE_BOOT_MIXINS = System.getProperty("sunlit.profiler.startupDir") != null;
    private static int prefetchedMixinCount;

    public static byte[] fastGetClassBytes(ModuleReader reader, ModuleReference module, String className) {
        if (reader == null || className == null) {
            return EMPTY_BYTE_ARRAY;
        }
        String path = className.replace('.', '/').concat(".class");
        var prefetched = prewarmedMixinBytes;
        if (module != null && !prefetched.isEmpty()) {
            var entries = prefetched.get(module);
            byte[] pre = entries == null ? null : entries.remove(path);
            if (pre != null) {
                return pre;
            }
        }
        return readClassBytesRaw(reader, path);
    }

    private static byte[] readClassBytesRaw(ModuleReader reader, String path) {
        if (reader instanceof java.util.function.Function fn) {
            try {
                byte[] direct = (byte[]) fn.apply(path);
                if (direct != null) {
                    return direct;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Optional<InputStream> opt = reader.open(path);
            if (opt.isEmpty()) {
                return EMPTY_BYTE_ARRAY;
            }
            try (InputStream in = opt.get()) {
                if (in instanceof ExactByteArrayInputStream eb) {
                    return eb.rawBytes();
                }
                return fastReadStreamExact(in);
            }
        } catch (IOException e) {
            throw sneakyThrow(e);
        }
    }

    public static byte[] fastReadStreamExact(InputStream in) throws IOException {
        if (in instanceof ExactByteArrayInputStream eb) {
            return eb.rawBytes();
        }
        int avail = in.available();
        if (avail > 0 && avail < (16 << 20)) {
            byte[] buf = new byte[avail];
            int off = 0;
            while (off < avail) {
                int r = in.read(buf, off, avail - off);
                if (r < 0) {
                    return Arrays.copyOf(buf, off);
                }
                off += r;
            }
            int extra = in.read();
            if (extra < 0) {
                return buf;
            }
            byte[] rest = in.readAllBytes();
            byte[] out = Arrays.copyOf(buf, avail + 1 + rest.length);
            out[avail] = (byte) extra;
            System.arraycopy(rest, 0, out, avail + 1, rest.length);
            return out;
        }
        return in.readAllBytes();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    public static String[] fastModFileTail(String[] path) {
        return path.length <= 1 ? EMPTY_STRING_ARRAY : Arrays.copyOfRange(path, 1, path.length);
    }

    public static boolean fastTryCleanSegments(String[] args) {
        if (args == null || args.length == 0) {
            return false;
        }
        for (int i = 0; i < args.length; i++) {
            String s = args[i];
            if (s == null || s.isEmpty() || s.indexOf('/') >= 0 || s.indexOf('\\') >= 0
                    || ".".equals(s) || "..".equals(s)) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasNoDotSegments(String[] parts) {
        if (parts == null) {
            return false;
        }
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i];
            if (s == null || ".".equals(s) || "..".equals(s)) {
                return false;
            }
        }
        return true;
    }

    private static final ConcurrentHashMap<String, java.util.regex.Pattern> PATTERN_CACHE =
            new ConcurrentHashMap<>(512);

    public static java.util.regex.Pattern fastCompilePattern(String regex) {
        java.util.regex.Pattern cached = PATTERN_CACHE.get(regex);
        if (cached != null) {
            return cached;
        }
        java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(regex);
        if (PATTERN_CACHE.size() < 2048) {
            java.util.regex.Pattern prev = PATTERN_CACHE.putIfAbsent(regex, compiled);
            if (prev != null) {
                return prev;
            }
        }
        return compiled;
    }

    public static boolean fastStringMatches(String input, String regex) {
        if (input == null || regex == null) {
            // Preserve exact String.matches NullPointerException semantics.
            input.length();
            regex.length();
        }
        if ("^-?\\d+(\\.\\d+)?$".equals(regex)) {
            return isAsciiDecimal(input);
        }
        if ("^(\\d|_).*".equals(regex)) {
            return matchesDigitOrUnderscorePrefix(input);
        }
        if ("(var|varb)\\.\\w+".equals(regex)) {
            return matchesEmfVarName(input);
        }
        if ("\\{\\s*\"parent\":\\s*\".*\"\\s*\\}".equals(regex)) {
            return fastMatchSimpleParentJson(input);
        }
        if (isLiteralRegex(regex)) {
            return input.equals(regex);
        }
        return fastCompilePattern(regex).matcher(input).matches();
    }

    private static boolean fastMatchSimpleParentJson(String s) {
        int len = s.length();
        int i = 0;
        while (i < len && isRegexSpace(s.charAt(i))) {
            i++;
        }
        if (i >= len || s.charAt(i) != '{') {
            return false;
        }
        i++;
        while (i < len && isRegexSpace(s.charAt(i))) {
            i++;
        }
        if (!s.startsWith("\"parent\":", i)) {
            return false;
        }
        i += 9;
        while (i < len && isRegexSpace(s.charAt(i))) {
            i++;
        }
        if (i >= len || s.charAt(i) != '"') {
            return false;
        }
        int openQuote = i;
        int j = len - 1;
        while (j > openQuote && isRegexSpace(s.charAt(j))) {
            j--;
        }
        if (j <= openQuote || s.charAt(j) != '}') {
            return false;
        }
        j--;
        while (j > openQuote && isRegexSpace(s.charAt(j))) {
            j--;
        }
        if (j <= openQuote || s.charAt(j) != '"') {
            return false;
        }
        for (int k = openQuote + 1; k < j; k++) {
            char c = s.charAt(k);
            if (c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                return false;
            }
        }
        return true;
    }

    private static boolean isRegexSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000B';
    }

    private static boolean matchesDigitOrUnderscorePrefix(String s) {
        int len = s.length();
        if (len == 0) {
            return false;
        }
        char c0 = s.charAt(0);
        if (!((c0 >= '0' && c0 <= '9') || c0 == '_')) {
            return false;
        }
        // Java '.' does not match line terminators (\n, \r, \u0085, \u2028, \u2029) unless DOTALL.
        for (int i = 1; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesEmfVarName(String s) {
        int start;
        if (s.startsWith("var.")) {
            start = 4;
        } else if (s.startsWith("varb.")) {
            start = 5;
        } else {
            return false;
        }
        int len = s.length();
        if (start >= len) {
            return false;
        }
        for (int i = start; i < len; i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiDecimal(String s) {
        int len = s.length();
        if (len == 0) {
            return false;
        }
        int i = 0;
        if (s.charAt(0) == '-') {
            if (len == 1) {
                return false;
            }
            i = 1;
        }
        int intDigits = 0;
        while (i < len) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                intDigits++;
                i++;
            } else {
                break;
            }
        }
        if (intDigits == 0) {
            return false;
        }
        if (i == len) {
            return true;
        }
        if (s.charAt(i) != '.') {
            return false;
        }
        i++;
        int fracDigits = 0;
        while (i < len) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                fracDigits++;
                i++;
            } else {
                return false;
            }
        }
        return fracDigits > 0;
    }

    public static String fastStringReplaceAll(String input, String regex, String replacement) {
        if (input == null || regex == null || replacement == null) {
            input.length();
            regex.length();
            replacement.length();
        }
        if (replacement.isEmpty()) {
            if ("\\s+".equals(regex) || "\\s".equals(regex)) {
                return stripAsciiRegexWhitespace(input);
            }
            if ("[^a-z0-9/._-]".equals(regex)) {
                return stripNonResourceLocationPathChars(input);
            }
        }
        if (!regex.isEmpty() && isLiteralRegex(regex)
                && replacement.indexOf('\\') < 0 && replacement.indexOf('$') < 0) {
            return input.replace(regex, replacement);
        }
        return fastCompilePattern(regex).matcher(input).replaceAll(replacement);
    }

    /** Returns true when Moonlight's replaceFullGenericType regex cannot possibly match {@code text}. */
    public static boolean canSkipMoonlightGenericReplace(String text, String targetKey, String modId) {
        if (text == null || text.isEmpty() || targetKey == null || targetKey.isEmpty() || !isLiteralRegex(targetKey)) {
            return false;
        }
        if (text.indexOf(targetKey) < 0) {
            return true;
        }
        return modId != null && !modId.isEmpty() && isLiteralRegex(modId)
                && (text.indexOf(':') < 0 || text.indexOf(modId) < 0);
    }

    private static final java.nio.file.LinkOption[] EMPTY_LINK_OPTIONS = new java.nio.file.LinkOption[0];

    /** Avoids per-call {@code LinkOption[0]} varargs allocation in {@code UnionFileSystem.testFilter}. */
    public static boolean fastIsDirectoryNoLinks(Path path) {
        if (path.getFileSystem() == java.nio.file.FileSystems.getDefault()) {
            return path.toFile().isDirectory();
        }
        return java.nio.file.Files.isDirectory(path, EMPTY_LINK_OPTIONS);
    }

    private static String stripNonResourceLocationPathChars(String s) {
        int len = s.length();
        int first = -1;
        for (int i = 0; i < len; i++) {
            if (!isResourceLocationPathChar(s.charAt(i))) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(len - 1);
        sb.append(s, 0, first);
        for (int i = first + 1; i < len; i++) {
            char c = s.charAt(i);
            if (isResourceLocationPathChar(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isResourceLocationPathChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-';
    }

    public static String fastStringReplaceFirst(String input, String regex, String replacement) {
        if (input == null || regex == null || replacement == null) {
            input.length();
            regex.length();
            replacement.length();
        }
        return fastCompilePattern(regex).matcher(input).replaceFirst(replacement);
    }

    public static String[] fastStringSplit(String input, String regex) {
        return fastStringSplitLimit(input, regex, 0);
    }

    public static String[] fastStringSplitLimit(String input, String regex, int limit) {
        if (input == null || regex == null) {
            input.length();
            regex.length();
        }
        if (limit == 0 && "(?!^)".equals(regex)) {
            int len = input.length();
            if (len == 0) {
                return new String[] {""};
            }
            String[] out = new String[len];
            for (int i = 0; i < len; i++) {
                out[i] = String.valueOf(input.charAt(i));
            }
            return out;
        }
        String literal = decodeSimpleSplitLiteral(regex);
        if (literal != null) {
            return splitByLiteral(input, literal, limit);
        }
        return fastCompilePattern(regex).split(input, limit);
    }

    private static String decodeSimpleSplitLiteral(String regex) {
        if (regex.isEmpty()) {
            return null;
        }
        if ("\\.".equals(regex)) {
            return ".";
        }
        if (regex.startsWith("\\[") && regex.length() > 2 && isLiteralRegex(regex.substring(2))) {
            return "[" + regex.substring(2);
        }
        // Only handle unescaped literal strings without ']'/'}' surprises for safety; ']' itself is literal in Java.
        if (isLiteralRegex(regex)) {
            return regex;
        }
        return null;
    }

    private static String[] splitByLiteral(String input, String delim, int limit) {
        int dlen = delim.length();
        int idx = input.indexOf(delim);
        if (idx < 0) {
            return new String[] {input};
        }
        ArrayList<String> list = new ArrayList<>(8);
        int off = 0;
        boolean limited = limit > 0;
        while (idx >= 0) {
            if (!limited || list.size() < limit - 1) {
                list.add(input.substring(off, idx));
                off = idx + dlen;
                idx = input.indexOf(delim, off);
            } else {
                list.add(input.substring(off));
                off = input.length();
                break;
            }
        }
        if (!limited || list.size() < limit) {
            list.add(input.substring(off));
        }
        int resultSize = list.size();
        if (limit == 0) {
            while (resultSize > 0 && list.get(resultSize - 1).isEmpty()) {
                resultSize--;
            }
        }
        String[] result = new String[resultSize];
        for (int i = 0; i < resultSize; i++) {
            result[i] = list.get(i);
        }
        return result;
    }

    public static String fastEmfProcessAnimKey(String expr, String partId, String partName) {
        if (expr == null) {
            expr.length();
        }
        String str = stripAsciiRegexWhitespace(expr.trim());
        if (str.isEmpty()) {
            return str;
        }
        if (str.indexOf("this") >= 0 && partId != null) {
            str = replaceWordBoundaryToken(str, "this", partId);
        }
        if (str.indexOf("part") >= 0 && partName != null) {
            str = replaceWordBoundaryToken(str, "part", partName);
        }
        return str;
    }

    /** Exact replacement for {@code (?<=\W|^)<token>(?=\W)} without Pattern/Matcher overhead. */
    private static String replaceWordBoundaryToken(String s, String token, String replacement) {
        int tlen = token.length();
        int len = s.length();
        int from = 0;
        StringBuilder sb = null;
        while (from <= len - tlen) {
            int idx = s.indexOf(token, from);
            if (idx < 0) {
                break;
            }
            int end = idx + tlen;
            boolean leftOk = idx == 0 || !isRegexWordChar(s.charAt(idx - 1));
            boolean rightOk = end < len && !isRegexWordChar(s.charAt(end));
            if (leftOk && rightOk) {
                if (sb == null) {
                    sb = new StringBuilder(len + 16);
                }
                sb.append(s, from, idx).append(replacement);
                from = end;
            } else {
                if (sb != null) {
                    sb.append(s, from, idx + 1);
                }
                from = idx + 1;
            }
        }
        if (sb == null) {
            return s;
        }
        sb.append(s, from, len);
        return sb.toString();
    }

    private static boolean isRegexWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static String stripAsciiRegexWhitespace(String s) {
        int len = s.length();
        int first = -1;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(len - 1);
        sb.append(s, 0, first);
        for (int i = first + 1; i < len; i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r' && c != '\f' && c != 0x0B) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Fast ASCII decimal parser for OBJ vertex/UV coordinates, falling back to {@link Float#parseFloat}. */
    public static float fastParseFloat(String s) {
        if (s == null) {
            return Float.parseFloat(null);
        }
        int len = s.length();
        int start = 0;
        while (start < len && s.charAt(start) <= ' ') {
            start++;
        }
        int end = len;
        while (end > start && s.charAt(end - 1) <= ' ') {
            end--;
        }
        if (start >= end) {
            return Float.parseFloat(s);
        }
        boolean neg = false;
        char first = s.charAt(start);
        if (first == '-') {
            neg = true;
            start++;
        } else if (first == '+') {
            start++;
        }
        if (start >= end) {
            return Float.parseFloat(s);
        }
        long mantissa = 0L;
        int sigDigits = 0;
        boolean seenDot = false;
        int fracDigits = 0;
        boolean sawDigit = false;
        int exp = 0;
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sawDigit = true;
                int d = c - '0';
                if (d != 0 || sigDigits > 0) {
                    sigDigits++;
                    if (sigDigits > 7) {
                        // Preserve exact IEEE-754 half-to-even rounding when exceeding 7 sig digits.
                        return Float.parseFloat(s);
                    }
                }
                mantissa = mantissa * 10L + d;
                if (seenDot) {
                    fracDigits++;
                }
            } else if (c == '.' && !seenDot) {
                seenDot = true;
            } else if ((c == 'e' || c == 'E') && sawDigit) {
                int j = i + 1;
                boolean expNeg = false;
                if (j < end) {
                    char ec = s.charAt(j);
                    if (ec == '-') {
                        expNeg = true;
                        j++;
                    } else if (ec == '+') {
                        j++;
                    }
                }
                if (j >= end) {
                    return Float.parseFloat(s);
                }
                int expVal = 0;
                for (; j < end; j++) {
                    char ec = s.charAt(j);
                    if (ec < '0' || ec > '9') {
                        return Float.parseFloat(s);
                    }
                    expVal = expVal * 10 + (ec - '0');
                    if (expVal > 10) {
                        return Float.parseFloat(s);
                    }
                }
                exp = expNeg ? -expVal : expVal;
                break;
            } else {
                return Float.parseFloat(s);
            }
        }
        if (!sawDigit) {
            return Float.parseFloat(s);
        }
        int netExp = exp - fracDigits;
        if (netExp < -10 || netExp > 10) {
            return Float.parseFloat(s);
        }
        double val = netExp >= 0
                ? (double) mantissa * OBJ_POW10[netExp]
                : (double) mantissa / OBJ_POW10[-netExp];
        return (float) (neg ? -val : val);
    }

    private static final double[] OBJ_POW10 = new double[] {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10
    };

    private static boolean isLiteralRegex(String regex) {
        for (int i = 0, n = regex.length(); i < n; i++) {
            switch (regex.charAt(i)) {
                case '\\':
                case '.':
                case '[':
                case '(':
                case '^':
                case '$':
                case '*':
                case '+':
                case '?':
                case '{':
                case '|':
                    return false;
                default:
                    break;
            }
        }
        return true;
    }

    private static final IdentityHashMap<Object, ConcurrentHashMap<String, List>> UNPACKAGED_RESOURCE_CACHE =
            new IdentityHashMap<>(8);
    private static final IdentityHashMap<Object, Set<String>> ROOT_RESOURCE_INDEX =
            new IdentityHashMap<>(8);
    private static final Set<String> ROOT_INDEX_INCOMPLETE = Set.of();
    private static volatile Object lastUnpackagedLoader;
    private static volatile ConcurrentHashMap<String, List> lastUnpackagedMap;

    public static boolean isKnownMissingRootResource(Object loader, Map<?, ?> resolvedRoots, String name) {
        if (loader == null || resolvedRoots == null || resolvedRoots.isEmpty() || name == null || name.isEmpty()) {
            return false;
        }
        String normalized = name.charAt(0) == '/' ? name.substring(1) : name;
        if (normalized.isEmpty()) {
            return false;
        }
        int slash = normalized.indexOf('/');
        String key;
        if (slash < 0) {
            key = normalized;
        } else if (normalized.startsWith("META-INF/services/") && normalized.indexOf('/', 18) < 0) {
            key = normalized;
        } else {
            return false;
        }
        Set<String> rootSet;
        synchronized (ROOT_RESOURCE_INDEX) {
            rootSet = ROOT_RESOURCE_INDEX.get(loader);
        }
        if (rootSet == null) {
            rootSet = buildRootResourceIndex(resolvedRoots);
            synchronized (ROOT_RESOURCE_INDEX) {
                Set<String> prev = ROOT_RESOURCE_INDEX.get(loader);
                if (prev == null) {
                    ROOT_RESOURCE_INDEX.put(loader, rootSet);
                } else {
                    rootSet = prev;
                }
            }
        }
        if (rootSet == ROOT_INDEX_INCOMPLETE) {
            return false;
        }
        return !rootSet.contains(key);
    }

    private static Set<String> buildRootResourceIndex(Map<?, ?> resolvedRoots) {
        try {
            Object[] refs = resolvedRoots.values().toArray();
            if (refs.length == 0) {
                return ROOT_INDEX_INCOMPLETE;
            }
            Set<String> out = ConcurrentHashMap.newKeySet(512);
            java.util.concurrent.atomic.AtomicBoolean incomplete = new java.util.concurrent.atomic.AtomicBoolean(false);
            parallelForEach(Arrays.asList(refs), obj -> {
                if (incomplete.get()) {
                    return;
                }
                Optional<java.net.URI> loc;
                if (obj instanceof java.lang.module.ModuleReference mref) {
                    loc = mref.location();
                } else if (obj instanceof java.lang.module.ResolvedModule rm) {
                    loc = rm.reference().location();
                } else {
                    incomplete.set(true);
                    return;
                }
                if (loc.isEmpty()) {
                    incomplete.set(true);
                    return;
                }
                java.net.URI uri = loc.get();
                if ("jrt".equalsIgnoreCase(uri.getScheme())) {
                    return;
                }
                try {
                    Path root = Path.of(uri);
                    if (Files.isDirectory(root)) {
                        boolean hasMetaInf = false;
                        try (var ds = Files.newDirectoryStream(root)) {
                            for (Path p : ds) {
                                Path fn = p.getFileName();
                                if (fn != null) {
                                    String s = fn.toString();
                                    if (!s.isEmpty() && s.charAt(s.length() - 1) == '/') {
                                        s = s.substring(0, s.length() - 1);
                                    }
                                    if (!s.isEmpty()) {
                                        out.add(s);
                                        if ("META-INF".equals(s)) {
                                            hasMetaInf = true;
                                        }
                                    }
                                }
                            }
                        }
                        if (hasMetaInf) {
                            Path svcDir = root.resolve("META-INF/services");
                            if (Files.isDirectory(svcDir)) {
                                try (var ds = Files.newDirectoryStream(svcDir)) {
                                    for (Path p : ds) {
                                        Path fn = p.getFileName();
                                        if (fn != null) {
                                            String s = fn.toString();
                                            if (!s.isEmpty() && s.charAt(s.length() - 1) != '/') {
                                                out.add("META-INF/services/" + s);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if ("file".equalsIgnoreCase(uri.getScheme()) && Files.isRegularFile(root)) {
                        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(root.toFile())) {
                            var entries = zf.entries();
                            while (entries.hasMoreElements()) {
                                String en = entries.nextElement().getName();
                                if (en.isEmpty()) {
                                    continue;
                                }
                                int sl = en.indexOf('/');
                                if (sl < 0) {
                                    out.add(en);
                                } else {
                                    out.add(en.substring(0, sl));
                                    if (en.startsWith("META-INF/services/") && en.length() > 18 && en.indexOf('/', 18) < 0) {
                                        out.add(en);
                                    }
                                }
                            }
                        }
                    } else {
                        incomplete.set(true);
                    }
                } catch (Throwable t) {
                    incomplete.set(true);
                }
            });
            return incomplete.get() ? ROOT_INDEX_INCOMPLETE : out;
        } catch (Throwable ignored) {
            return ROOT_INDEX_INCOMPLETE;
        }
    }

    private static final class CachedListSnapshot {
        final List<?> list;
        final int size;
        final Object first;
        final Object last;
        final Set<Object> set;

        CachedListSnapshot(List<?> list, int size, Object first, Object last, Set<Object> set) {
            this.list = list;
            this.size = size;
            this.first = first;
            this.last = last;
            this.set = set;
        }
    }

    private static volatile CachedListSnapshot cachedListA;
    private static volatile CachedListSnapshot cachedListB;

    /** O(1) snapshot-backed replacement for hot repeated {@link List#contains} config checks. */
    public static boolean fastCachedListContains(List<?> list, Object key) {
        if (list == null) {
            return false;
        }
        int size = list.size();
        if (size == 0) {
            return false;
        }
        if (size < 4 || !(list instanceof java.util.RandomAccess)) {
            return list.contains(key);
        }
        Object first = list.get(0);
        Object last = list.get(size - 1);
        CachedListSnapshot snap = cachedListA;
        if (snap != null && snap.list == list && snap.size == size && snap.first == first && snap.last == last) {
            return snap.set.contains(key);
        }
        snap = cachedListB;
        if (snap != null && snap.list == list && snap.size == size && snap.first == first && snap.last == last) {
            return snap.set.contains(key);
        }
        Set<Object> set = new HashSet<>(Math.max(16, (size * 4) / 3 + 1));
        for (int i = 0; i < size; i++) {
            set.add(list.get(i));
        }
        CachedListSnapshot created = new CachedListSnapshot(list, size, first, last, set);
        if (cachedListA == null || cachedListA.list == list) {
            cachedListA = created;
        } else {
            cachedListB = created;
        }
        return set.contains(key);
    }

    private static volatile boolean rtssCheckAttempted;
    private static volatile java.lang.reflect.Method kernelGetModuleHandle;
    private static volatile Object kernelInstance;

    /** Fast Win32 {@code Kernel32.GetModuleHandle} probe avoiding full 150-DLL version scanning when RTSS is absent. */
    public static boolean mayHaveRtssHooksLoaded(ClassLoader callerLoader) {
        try {
            java.lang.reflect.Method m = kernelGetModuleHandle;
            Object inst = kernelInstance;
            if (m == null) {
                if (rtssCheckAttempted) {
                    return true;
                }
                rtssCheckAttempted = true;
                ClassLoader cl = callerLoader != null ? callerLoader : Thread.currentThread().getContextClassLoader();
                Class<?> k32 = Class.forName("com.sun.jna.platform.win32.Kernel32", false, cl);
                inst = k32.getField("INSTANCE").get(null);
                m = k32.getMethod("GetModuleHandle", String.class);
                kernelInstance = inst;
                kernelGetModuleHandle = m;
            }
            return m.invoke(inst, "RTSSHooks64.dll") != null || m.invoke(inst, "RTSSHooks.dll") != null;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static ConcurrentHashMap<String, List> getUnpackagedResourceMap(Object loader, boolean create) {
        if (loader == lastUnpackagedLoader) {
            ConcurrentHashMap<String, List> fast = lastUnpackagedMap;
            if (fast != null) {
                return fast;
            }
        }
        synchronized (UNPACKAGED_RESOURCE_CACHE) {
            ConcurrentHashMap<String, List> map = UNPACKAGED_RESOURCE_CACHE.get(loader);
            if (map == null && create) {
                map = new ConcurrentHashMap<>(128);
                UNPACKAGED_RESOURCE_CACHE.put(loader, map);
            }
            if (map != null) {
                lastUnpackagedLoader = loader;
                lastUnpackagedMap = map;
            }
            return map;
        }
    }

    public static List getCachedUnpackagedResources(Object loader, String name) {
        if (loader == null || name == null) {
            return null;
        }
        ConcurrentHashMap<String, List> map = getUnpackagedResourceMap(loader, false);
        return map != null ? map.get(name) : null;
    }

    public static List cacheUnpackagedResources(Object loader, String name, List result) {
        if (loader == null || name == null || result == null) {
            return result;
        }
        ConcurrentHashMap<String, List> map = getUnpackagedResourceMap(loader, true);
        if (map.size() < 4096) {
            List prev = map.putIfAbsent(name, result);
            if (prev != null) {
                return prev;
            }
        }
        return result;
    }

    private static final byte[] PNG_SIGNATURE =
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PNG_IEND = new byte[] {
        0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };

    /** Fast PNG encoder avoiding {@code javax.imageio.ImageIO} SPI initialization on startup icons. */
    public static boolean fastWritePng(Object imageObj, String format, java.io.OutputStream out)
            throws IOException {
        if ("png".equalsIgnoreCase(format) && imageObj instanceof java.awt.image.BufferedImage img && out != null) {
            int w = img.getWidth();
            int h = img.getHeight();
            if (w > 0 && h > 0 && w <= 4096 && h <= 4096) {
                int[] pixels = new int[w * h];
                img.getRGB(0, 0, w, h, pixels, 0, w);
                int stride = 1 + w * 4;
                byte[] raw = new byte[h * stride];
                int pIdx = 0;
                int rIdx = 0;
                for (int y = 0; y < h; y++) {
                    raw[rIdx++] = 0;
                    for (int x = 0; x < w; x++) {
                        int argb = pixels[pIdx++];
                        raw[rIdx++] = (byte) (argb >>> 16);
                        raw[rIdx++] = (byte) (argb >>> 8);
                        raw[rIdx++] = (byte) argb;
                        raw[rIdx++] = (byte) (argb >>> 24);
                    }
                }
                java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream(raw.length / 2 + 64);
                java.util.zip.Deflater deflater = new java.util.zip.Deflater(1);
                try {
                    deflater.setInput(raw);
                    deflater.finish();
                    byte[] buf = new byte[4096];
                    while (!deflater.finished()) {
                        int n = deflater.deflate(buf);
                        if (n > 0) {
                            compressed.write(buf, 0, n);
                        } else if (deflater.needsInput()) {
                            break;
                        }
                    }
                } finally {
                    deflater.end();
                }
                byte[] idat = compressed.toByteArray();
                byte[] ihdr = new byte[13];
                putBigEndianInt(ihdr, 0, w);
                putBigEndianInt(ihdr, 4, h);
                ihdr[8] = 8;
                ihdr[9] = 6;
                out.write(PNG_SIGNATURE);
                writePngChunk(out, 0x49484452, ihdr);
                writePngChunk(out, 0x49444154, idat);
                out.write(PNG_IEND);
                return true;
            }
        }
        try {
            Class<?> ioCls = Class.forName("javax.imageio.ImageIO");
            Class<?> riCls = Class.forName("java.awt.image.RenderedImage");
            java.lang.reflect.Method m = ioCls.getMethod("write", riCls, String.class, java.io.OutputStream.class);
            Object ok = m.invoke(null, imageObj, format, out);
            return Boolean.TRUE.equals(ok);
        } catch (Throwable t) {
            if (t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("ImageIO fallback failed", t);
        }
    }

    private static void writePngChunk(java.io.OutputStream out, int type, byte[] data) throws IOException {
        byte[] header = new byte[8];
        putBigEndianInt(header, 0, data.length);
        putBigEndianInt(header, 4, type);
        out.write(header);
        out.write(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(header, 4, 4);
        crc.update(data);
        int c = (int) crc.getValue();
        byte[] crcBytes = new byte[4];
        putBigEndianInt(crcBytes, 0, c);
        out.write(crcBytes);
    }

    private static void putBigEndianInt(byte[] buf, int off, int val) {
        buf[off] = (byte) (val >>> 24);
        buf[off + 1] = (byte) (val >>> 16);
        buf[off + 2] = (byte) (val >>> 8);
        buf[off + 3] = (byte) val;
    }

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private static final IdentityHashMap<List<String>, HashMap<String, String>> MIXIN_CLASS_UID_MAP =
            new IdentityHashMap<>(4);

    /** O(1) replacement for Mixin MethodMapper.getClassUID linear ArrayList.indexOf scan. */
    public static String fastMixinClassUID(List<String> classes, String name) {
        if (classes == null) {
            return null;
        }
        synchronized (classes) {
            HashMap<String, String> cache = MIXIN_CLASS_UID_MAP.get(classes);
            if (cache == null) {
                cache = new HashMap<>(512);
                for (int i = 0, n = classes.size(); i < n; i++) {
                    cache.putIfAbsent(classes.get(i), finagleMixinUid(i));
                }
                MIXIN_CLASS_UID_MAP.put(classes, cache);
            }
            String uid = cache.get(name);
            if (uid == null) {
                int idx = classes.size();
                classes.add(name);
                uid = finagleMixinUid(idx);
                cache.put(name, uid);
            }
            return uid;
        }
    }

    private static String finagleMixinUid(int index) {
        String hex = Integer.toHexString(index);
        int len = hex.length();
        int outLen = len < 3 ? 3 : len;
        char[] buf = new char[outLen];
        int pad = outLen - len;
        for (int i = 0; i < pad; i++) {
            buf[i] = 'z';
        }
        for (int i = 0; i < len; i++) {
            char c = hex.charAt(i);
            buf[pad + i] = (char) (c + (c < ':' ? 49 : 10));
        }
        return new String(buf);
    }

    /** Mathematical length-bound check for Cupboard ClientConfigCompat (> 0.9 similarity). */
    public static boolean shouldSkipCupboardSimilarity(String a, String b) {
        if (a == null || b == null) {
            return true;
        }
        int la = a.length();
        int lb = b.length();
        int max = la >= lb ? la : lb;
        if (max == 0) {
            return false;
        }
        int diff = la >= lb ? la - lb : lb - la;
        return diff * 10 >= max;
    }

    /** Linear-time replacement for FancyMenu PropertiesParser.deserializeSetFromFile O(N^2) String concat. */
    public static String fastJoinFancyLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        int approx = 0;
        for (int i = 0, n = lines.size(); i < n; i++) {
            String s = lines.get(i);
            approx += (s != null ? s.length() : 4) + 1;
        }
        StringBuilder sb = new StringBuilder(approx);
        for (int i = 0, n = lines.size(); i < n; i++) {
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }

    /** Zero-regex replacement for {@code line.replaceAll("[\\p{Z}\\s]+", "")} in FancyMenu PropertiesParser. */
    public static String fastStripFancyWhitespace(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        int len = line.length();
        int firstWs = -1;
        for (int i = 0; i < len; i++) {
            if (isFancyPropertyWhitespace(line.charAt(i))) {
                firstWs = i;
                break;
            }
        }
        if (firstWs < 0) {
            return line;
        }
        StringBuilder sb = new StringBuilder(len - 1);
        sb.append(line, 0, firstWs);
        for (int i = firstWs + 1; i < len; i++) {
            char c = line.charAt(i);
            if (!isFancyPropertyWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isFancyPropertyWhitespace(char c) {
        if (c <= ' ') {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000B';
        }
        if (c < 0x80) {
            return false;
        }
        int type = Character.getType(c);
        return type == Character.SPACE_SEPARATOR
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    public static final Object RENDERER_LOOKUP_FAILED = new Object();
    private static volatile MethodHandle addLayersRenderersGetter;
    private static volatile Class<?> livingRendererClass;

    /**
     * Checks {@code EntityRenderersEvent$AddLayers.renderers} without throwing and catching
     * {@link ClassCastException} or logging 75+ warnings on the Render thread.
     */
    @SuppressWarnings("rawtypes")
    public static Object fastSafeGetLivingRenderer(Object addLayersEvent, Object entityType) {
        if (addLayersEvent == null || entityType == null) {
            return null;
        }
        try {
            MethodHandle getter = addLayersRenderersGetter;
            Class<?> livingCls = livingRendererClass;
            if (getter == null || livingCls == null) {
                Class<?> evCls = addLayersEvent.getClass();
                Field f = evCls.getDeclaredField("renderers");
                f.setAccessible(true);
                getter = MethodHandles.lookup().unreflectGetter(f);
                livingCls = Class.forName(
                        "net.minecraft.client.renderer.entity.LivingEntityRenderer",
                        false,
                        evCls.getClassLoader());
                addLayersRenderersGetter = getter;
                livingRendererClass = livingCls;
            }
            Map renderers = (Map) getter.invoke(addLayersEvent);
            if (renderers == null) {
                return null;
            }
            Object renderer = renderers.get(entityType);
            return livingCls.isInstance(renderer) ? renderer : null;
        } catch (Throwable ignored) {
            return RENDERER_LOOKUP_FAILED;
        }
    }

    /** Exact ASCII replacement for {@code StateDefinition.NAME_PATTERN} ({@code ^[a-z0-9_]+$}). */
    public static boolean isValidStateName(String s) {
        if (s == null) {
            return false;
        }
        int len = s.length();
        if (len == 0) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** Fast sequential add path for {@code IdMapper.addMapping} avoiding {@code add(null)} + {@code set}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void fastIdMapperAddToList(List list, int id, Object value) {
        int size = list.size();
        if (id == size) {
            if (size == 512 && list instanceof ArrayList al) {
                al.ensureCapacity(8192);
            }
            list.add(value);
            return;
        }
        while (size <= id) {
            list.add(null);
            size++;
        }
        list.set(id, value);
    }

    /** Zero-copy view for {@code Configuration.reads(ResolvedModule)} when backed by an immutable Set. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Set fastUnmodifiableSet(java.util.Collection col) {
        if (col instanceof Set s) {
            return java.util.Collections.unmodifiableSet(s);
        }
        return Set.copyOf(col);
    }

    /** Resource-based class existence check avoiding full Mixin ClassNode parse/transform on startup. */
    public static boolean classResourceExists(String binaryName, ClassLoader ownerCl) {
        if (binaryName == null || binaryName.isEmpty()) {
            return false;
        }
        String res = binaryName.replace('.', '/') + ".class";
        if (ownerCl != null && ownerCl.getResource(res) != null) {
            return true;
        }
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        return ctx != null && ctx != ownerCl && ctx.getResource(res) != null;
    }

    private static volatile byte[] FLAT_STATE_MAP_BYTES;

    public static void setFlatStateMapBytes(byte[] bytes) {
        FLAT_STATE_MAP_BYTES = bytes;
    }

    /** Defines {@code SunlitFlatStateMap} once inside Guava's {@code ImmutableMap} lookup package. */
    @SuppressWarnings("unchecked")
    public static BiFunction<Object[], Object[], Object> initFlatStateMapFactory(java.lang.invoke.MethodHandles.Lookup lookup) {
        byte[] bytes = FLAT_STATE_MAP_BYTES;
        if (bytes == null || lookup == null) {
            return null;
        }
        try {
            Class<?> imCls = lookup.lookupClass();
            Class<?> flatCls;
            try {
                flatCls = Class.forName("com.google.common.collect.SunlitFlatStateMap", false, imCls.getClassLoader());
            } catch (ClassNotFoundException e) {
                flatCls = lookup.defineClass(bytes);
            }
            java.lang.invoke.MethodHandle raw = lookup.findConstructor(
                    flatCls,
                    java.lang.invoke.MethodType.methodType(void.class, Object[].class, Object[].class));
            try {
                return (BiFunction<Object[], Object[], Object>) java.lang.invoke.LambdaMetafactory.metafactory(
                        lookup, "apply", java.lang.invoke.MethodType.methodType(BiFunction.class),
                        java.lang.invoke.MethodType.methodType(Object.class, Object.class, Object.class),
                        raw, raw.type()).getTarget().invokeExact();
            } catch (java.lang.invoke.LambdaConversionException | LinkageError ignored) {
                java.lang.invoke.MethodHandle fallback = raw.asType(raw.type().changeReturnType(Object.class));
                return (keys, values) -> {
                    try {
                        return (Object) fallback.invokeExact(keys, values);
                    } catch (Throwable e) {
                        throw sneakyThrow(e);
                    }
                };
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Fast array equality for {@code SunlitFlatStateMap.equals} when keys arrays are identical. */
    public static boolean flatArrayEquals(Object[] a, Object[] b) {
        return Arrays.equals(a, b);
    }

    /** Set by {@code IrisShaderLoadPerf} while background shaderpack loading is deferred/active so {@code ClientLanguage} lookups never block startup. */
    public static volatile boolean irisDeferredPending = false;

    public static final byte[] EMPTY_ZIP_BYTES = new byte[0];
    private static final byte[] ROOT_SLASH_BYTES = new byte[] { '/' };

    /**
     * Zero-encoder ASCII fast path producing an absolute normalized UTF-8 path ({@code /...}) for
     * {@code jdk.nio.zipfs.ZipFileSystem.exists(byte[])}, avoiding {@code ZipPath} and
     * {@code toAbsolutePath()} allocations on cache/union lookups. Returns {@code null} on
     * {@code .} / {@code ..} segments, backslashes, double slashes, NULs, or non-ASCII characters.
     */
    public static byte[] fastZipAbsUtf8Path(String path) {
        if (path == null) {
            return null;
        }
        int len = path.length();
        if (len == 0) {
            return ROOT_SLASH_BYTES;
        }
        if (path.charAt(0) == '/') {
            if (len > 1 && path.charAt(1) == '.') {
                return null;
            }
            for (int i = 1; i < len - 1; i++) {
                if (path.charAt(i) == '/' && path.charAt(i + 1) == '.') {
                    return null;
                }
            }
            return fastZipNormalizeAscii(path);
        }
        if (path.charAt(0) == '.') {
            return null;
        }
        int end = len;
        if (len > 1 && path.charAt(len - 1) == '/') {
            if (path.charAt(len - 2) == '/') {
                return null;
            }
            end = len - 1;
        }
        byte[] out = new byte[end + 1];
        out[0] = (byte) '/';
        boolean prevSlash = false;
        for (int i = 0; i < end; i++) {
            char c = path.charAt(i);
            if (c >= 0x80 || c == '\\' || c == 0) {
                return null;
            }
            if (c == '/') {
                if (prevSlash) {
                    return null;
                }
                prevSlash = true;
            } else if (prevSlash && c == '.') {
                return null;
            } else {
                prevSlash = false;
            }
            out[i + 1] = (byte) c;
        }
        return out;
    }

    private static final ThreadLocal<java.util.zip.Inflater> TL_ENTRY_INFLATER =
            ThreadLocal.withInitial(() -> new java.util.zip.Inflater(true));
    private static final ThreadLocal<byte[]> TL_ENTRY_IN_BUF =
            ThreadLocal.withInitial(() -> new byte[8192]);
    private static final ThreadLocal<byte[]> TL_SCAN_OUT_BUF =
            ThreadLocal.withInitial(() -> new byte[65536]);

    /**
     * Direct thread-local entry decompressor called from transformed {@code ZipFileSystem.apply}.
     * Avoids allocating {@code ZipFileSystem$2}, per-entry {@code byte[8192]} buffers, and
     * synchronized {@code streams}/{@code inflaters} set updates on cold startup scans.
     */
    public static byte[] inflateEntryStream(
            InputStream eis, int method, int uncompressedSize, int[] lenOut) {
        if (eis == null || uncompressedSize < 0) {
            if (eis != null) {
                try {
                    eis.close();
                } catch (IOException ignored) {
                }
            }
            return null;
        }
        try {
            byte[] out = (lenOut != null && uncompressedSize <= 65536)
                    ? TL_SCAN_OUT_BUF.get()
                    : new byte[uncompressedSize];
            int pos = 0;
            if (method == 0) {
                while (pos < uncompressedSize) {
                    int r = eis.read(out, pos, uncompressedSize - pos);
                    if (r < 0) {
                        return null;
                    }
                    pos += r;
                }
                if (lenOut != null) {
                    lenOut[0] = uncompressedSize;
                }
                return out;
            }
            if (method == 8) {
                java.util.zip.Inflater inf = TL_ENTRY_INFLATER.get();
                byte[] inBuf = TL_ENTRY_IN_BUF.get();
                try {
                    inf.reset();
                    if (uncompressedSize > 0) {
                        int first = eis.read(inBuf, 0, inBuf.length);
                        if (first < 0) {
                            return null;
                        }
                        inf.setInput(inBuf, 0, first);
                    }
                    while (pos < uncompressedSize && !inf.finished()) {
                        int n = inf.inflate(out, pos, uncompressedSize - pos);
                        if (n > 0) {
                            pos += n;
                            continue;
                        }
                        if (inf.needsDictionary()) {
                            return null;
                        }
                        if (inf.needsInput()) {
                            int r = eis.read(inBuf, 0, inBuf.length);
                            if (r < 0) {
                                return null;
                            }
                            inf.setInput(inBuf, 0, r);
                        }
                    }
                    if (pos != uncompressedSize) {
                        return null;
                    }
                    if (lenOut != null) {
                        lenOut[0] = uncompressedSize;
                    }
                    return out;
                } catch (java.util.zip.DataFormatException e) {
                    return null;
                } finally {
                    inf.reset();
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            try {
                eis.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Zero-allocation scratch read for FML class scanning when entry fits within 64 KB. */
    public static byte[] readScanClassBytes(Path file, int[] lenOut) throws IOException {
        if (file == null) {
            throw new NullPointerException("path");
        }
        if (file.getFileSystem() instanceof java.util.function.BiFunction bf) {
            byte[] fast = (byte[]) bf.apply(file, lenOut);
            if (fast != null) {
                return fast;
            }
        }
        byte[] exact = readAllBytesExact(file);
        lenOut[0] = exact.length;
        return exact;
    }

    /** Exact-sized stream read helper avoiding ASM ClassReader array growth. */
    public static byte[] readAllBytesExact(Path file) throws IOException {
        if (file == null) {
            throw new NullPointerException("path");
        }
        if (file.getFileSystem() instanceof java.util.function.BiFunction bf) {
            byte[] fast = (byte[]) bf.apply(file, null);
            if (fast != null) {
                return fast;
            }
        }
        try (InputStream in = Files.newInputStream(file)) {
            return fastReadStreamExact(in);
        }
    }

    private static final ThreadLocal<byte[]> TL_SMALL_UTF8_BUF = ThreadLocal.withInitial(() -> new byte[8192]);

    /**
     * Fast {@code BufferedReader} for small UTF-8 resource/metadata streams ({@code <= 8 KB}): avoids
     * allocating per-file {@code BufferedReader(8 KB char[])} and {@code StreamDecoder(8 KB byte[])}.
     */
    public static java.io.BufferedReader fastUtf8BufferedReader(InputStream stream) throws IOException {
        if (stream == null) {
            throw new NullPointerException("stream");
        }
        byte[] buf = TL_SMALL_UTF8_BUF.get();
        int len = 0;
        try {
            while (len < buf.length) {
                int r = stream.read(buf, len, buf.length - len);
                if (r < 0) {
                    String text = new String(buf, 0, len, java.nio.charset.StandardCharsets.UTF_8);
                    stream.close();
                    return new java.io.BufferedReader(new java.io.StringReader(text), 1);
                }
                len += r;
            }
            int extra = stream.read();
            if (extra < 0) {
                String text = new String(buf, 0, len, java.nio.charset.StandardCharsets.UTF_8);
                stream.close();
                return new java.io.BufferedReader(new java.io.StringReader(text), 1);
            }
            byte[] firstChunk = new byte[len + 1];
            System.arraycopy(buf, 0, firstChunk, 0, len);
            firstChunk[len] = (byte) extra;
            InputStream combined = new java.io.SequenceInputStream(
                    new java.io.ByteArrayInputStream(firstChunk), stream);
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(combined, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /**
     * Compact replacement for {@code Map.copyOf(children)} in ModernFix's
     * {@code PackResourcesCacheEngine$Node.optimize()}: avoids allocating {@code MapN}/entry arrays
     * across ~50k cached resource directory nodes.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static java.util.Map fastPackCacheMapCopy(java.util.Map map) {
        if (map == null || map.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        if (map.size() == 1) {
            java.util.Map.Entry e = (java.util.Map.Entry) map.entrySet().iterator().next();
            return java.util.Collections.singletonMap(e.getKey(), e.getValue());
        }
        return map;
    }

    /**
     * Zero-encoder ASCII fast path for {@code jdk.nio.zipfs.ZipPath.normalize(String)} on UTF-8 JAR
     * filesystems. Returns {@code null} on non-ASCII characters, backslashes, NULs, double slashes,
     * or multi-trailing slashes so the original slow path handles full normalization.
     */
    public static byte[] fastZipNormalizeAscii(String path) {
        int len = path.length();
        if (len == 0) {
            return EMPTY_ZIP_BYTES;
        }
        int end = len;
        if (len > 1 && path.charAt(len - 1) == '/') {
            if (path.charAt(len - 2) == '/') {
                return null;
            }
            end = len - 1;
        }
        byte[] out = new byte[end];
        boolean prevSlash = false;
        for (int i = 0; i < end; i++) {
            char c = path.charAt(i);
            if (c >= 0x80 || c == '\\' || c == 0) {
                return null;
            }
            if (c == '/') {
                if (prevSlash) {
                    return null;
                }
                prevSlash = true;
            } else {
                prevSlash = false;
            }
            out[i] = (byte) c;
        }
        return out;
    }

    /**
     * Zero-regex/zero-stream replacement for Forge {@code ObjModel.parse} face vertex parsing:
     * {@code Arrays.stream(token.split("/")).mapToInt(s -> Strings.isNullOrEmpty(s) ? 0 : Integer.parseInt(s)).toArray()}.
     */
    public static int[] fastParseObjFaceVertex(String token) {
        if (token == null || token.isEmpty()) {
            return new int[] {0};
        }
        int len = token.length();
        // Match String.split("/") trailing-empty trimming so "12/" yields [12] while "12//9" yields [12, 0, 9].
        int end = len;
        while (end > 0 && token.charAt(end - 1) == '/') {
            end--;
        }
        if (end == 0) {
            return new int[0];
        }
        int s1 = token.indexOf('/');
        if (s1 < 0 || s1 >= end) {
            return new int[] {Integer.parseInt(token, 0, end, 10)};
        }
        int s2 = token.indexOf('/', s1 + 1);
        if (s2 < 0 || s2 >= end) {
            int p0 = (s1 == 0) ? 0 : Integer.parseInt(token, 0, s1, 10);
            int p1 = Integer.parseInt(token, s1 + 1, end, 10);
            return new int[] {p0, p1};
        }
        int s3 = token.indexOf('/', s2 + 1);
        if (s3 < 0 || s3 >= end) {
            int p0 = (s1 == 0) ? 0 : Integer.parseInt(token, 0, s1, 10);
            int p1 = (s2 == s1 + 1) ? 0 : Integer.parseInt(token, s1 + 1, s2, 10);
            int p2 = Integer.parseInt(token, s2 + 1, end, 10);
            return new int[] {p0, p1, p2};
        }
        String[] parts = token.split("/");
        int[] res = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            res[i] = (p == null || p.isEmpty()) ? 0 : Integer.parseInt(p);
        }
        return res;
    }

    private static final ConcurrentHashMap<Object, Object> SCAN_TYPES = new ConcurrentHashMap<>(16384);

    /** Thread-safe replacement for {@code ModFileScanDataCompactor.TYPES.addOrGet}. */
    public static Object internScanType(Object type) {
        if (type == null) {
            return null;
        }
        Object prev = SCAN_TYPES.putIfAbsent(type, type);
        return prev != null ? prev : type;
    }

    public static Function<Object, Object> internScanTypeFn() {
        return BootJarPayload::internScanType;
    }

    public static void clearScanTypes() {
        SCAN_TYPES.clear();
    }

    /** Executes independent per-item work in parallel across {@code ForkJoinPool.commonPool()}. */
    public static void parallelForEach(List items, java.util.function.Consumer action) {
        if (items == null || items.isEmpty() || action == null) {
            return;
        }
        int n = items.size();
        if (n < 16) {
            for (int i = 0; i < n; i++) {
                action.accept(items.get(i));
            }
            return;
        }
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        int workers = Math.min(n, Math.max(2, Runtime.getRuntime().availableProcessors()));
        int chunk = Math.max(4, (n + workers - 1) / workers);
        List<ForkJoinTask<?>> tasks = new ArrayList<>(workers);
        for (int s = chunk; s < n; s += chunk) {
            final int start = s;
            final int end = Math.min(n, s + chunk);
            tasks.add(ForkJoinPool.commonPool().submit(() -> {
                Thread cur = Thread.currentThread();
                ClassLoader prevCl = cur.getContextClassLoader();
                if (prevCl != tccl) {
                    cur.setContextClassLoader(tccl);
                }
                try {
                    for (int i = start; i < end; i++) {
                        action.accept(items.get(i));
                    }
                } finally {
                    if (cur.getContextClassLoader() != prevCl) {
                        cur.setContextClassLoader(prevCl);
                    }
                }
            }));
        }
        int firstEnd = Math.min(n, chunk);
        for (int i = 0; i < firstEnd; i++) {
            action.accept(items.get(i));
        }
        for (int i = 0; i < tasks.size(); i++) {
            tasks.get(i).join();
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean POST_BOOT_NET_STARTED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean POST_BOOT_RT_STARTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Offloads post-bootstrap Forge/RenderType class init off {@code BackgroundWaiter}. */
    public static void schedulePostBootstrapPrewarm(boolean loadRenderType) {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl == null) {
            return;
        }
        if (loadRenderType) {
            if (!POST_BOOT_RT_STARTED.compareAndSet(false, true)) {
                return;
            }
            Thread t = new Thread(() -> {
                try {
                    Class<?> cls = Class.forName("net.minecraft.client.renderer.RenderType", true, tccl);
                    cls.getMethod("m_110451_").invoke(null);
                } catch (Throwable ignored) {
                }
            }, "Sunlit-PostBootstrap-RT");
            t.setDaemon(true);
            t.setContextClassLoader(tccl);
            t.start();
            return;
        }
        if (!POST_BOOT_NET_STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Class<?> cls = Class.forName("net.minecraftforge.network.NetworkConstants", true, tccl);
                cls.getMethod("init").invoke(null);
            } catch (Throwable ignored) {
            }
        }, "Sunlit-PostBootstrap-Net");
        t.setDaemon(true);
        t.setContextClassLoader(tccl);
        t.start();
    }

    /** Single-pass replacement for CarryOn {@code ListHandler.addWithWildcards}. */
    public static void applyCarryOnWildcards(List<String> list, Set<String> set, Set<?> registryKeys) {
        if (list == null || list.isEmpty() || set == null) {
            return;
        }
        ArrayList<String[]> filters = null;
        for (int i = 0, n = list.size(); i < n; i++) {
            String curr = list.get(i);
            if (curr == null || curr.startsWith("#")) {
                continue;
            }
            if (!curr.contains("*")) {
                set.add(curr);
            } else {
                if (filters == null) {
                    filters = new ArrayList<>();
                }
                filters.add(curr.replace("*", ",").split(","));
            }
        }
        if (filters == null || filters.isEmpty() || registryKeys == null || registryKeys.isEmpty()) {
            return;
        }
        int filterCount = filters.size();
        for (Object keyObj : registryKeys) {
            if (keyObj == null) {
                continue;
            }
            String s = keyObj.toString();
            for (int f = 0; f < filterCount; f++) {
                if (matchesCarryOnWildcard(s, filters.get(f))) {
                    set.add(s);
                    break;
                }
            }
        }
    }

    private static boolean matchesCarryOnWildcard(String str, String[] keys) {
        int pos = 0;
        for (int i = 0, n = keys.length; i < n; i++) {
            String k = keys[i];
            int idx = str.indexOf(k, pos);
            if (idx < 0) {
                return false;
            }
            pos = idx + k.length();
        }
        return true;
    }

    /** Parallel pre-read of pending {@code MixinConfig} class bytes prior to {@code MixinProcessor.select}. */
    public static void prewarmPendingMixinConfigs(Object processor) {
        if (processor == null) {
            return;
        }
        try {
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            if (tccl == null) {
                return;
            }
            Object pendingObj = readDeclaredFieldValue(processor.getClass(), processor, "pendingConfigs");
            if (!(pendingObj instanceof List<?> pending) || pending.isEmpty()) {
                return;
            }
            Object pkgLookupObj = findDeclaredFieldValue(tccl.getClass(), tccl, "packageLookup");
            if (!(pkgLookupObj instanceof Map<?, ?> pkgLookup) || pkgLookup.isEmpty()) {
                if (TRACE_BOOT_MIXINS) System.err.println("[SunlitBootAgent] Mixin prefetch: no module package lookup");
                return;
            }
            boolean isClient = detectClientDist(tccl);
            ArrayList<String> fullNames = new ArrayList<>(1024);
            HashSet<String> seenPaths = new HashSet<>(1536);
            Field pkgField = null;
            Field commonField = null;
            Field sideField = null;
            String sideFieldName = isClient ? "mixinClassesClient" : "mixinClassesServer";
            for (int i = 0, n = pending.size(); i < n; i++) {
                Object cfg = pending.get(i);
                if (cfg == null) {
                    continue;
                }
                if (pkgField == null) {
                    Class<?> cfgCls = cfg.getClass();
                    pkgField = getDeclaredFieldQuiet(cfgCls, "mixinPackage");
                    commonField = getDeclaredFieldQuiet(cfgCls, "mixinClasses");
                    sideField = getDeclaredFieldQuiet(cfgCls, sideFieldName);
                }
                Object pkgVal = readFieldValue(pkgField, cfg);
                if (!(pkgVal instanceof String pkg) || pkg.isEmpty()) {
                    continue;
                }
                String prefix = pkg.endsWith(".") ? pkg : pkg + ".";
                collectMixinFullNames(readFieldValue(commonField, cfg), prefix, seenPaths, fullNames);
                collectMixinFullNames(readFieldValue(sideField, cfg), prefix, seenPaths, fullNames);
            }
            if (fullNames.size() < 16) {
                if (TRACE_BOOT_MIXINS) System.err.println("[SunlitBootAgent] Mixin prefetch: " + fullNames.size() + " declared mixins");
                return;
            }
            Map<ModuleReference, List<String>> pathsByModule = new HashMap<>();
            for (String fullName : fullNames) {
                int dot = fullName.lastIndexOf('.');
                if (dot <= 0) {
                    continue;
                }
                String pkgName = fullName.substring(0, dot);
                Object modObj = pkgLookup.get(pkgName);
                if (!(modObj instanceof java.lang.module.ResolvedModule rm)) {
                    continue;
                }
                String path = fullName.replace('.', '/').concat(".class");
                pathsByModule.computeIfAbsent(rm.reference(), ignored -> new ArrayList<>()).add(path);
            }
            List<MixinReadBatch> batches = new ArrayList<>();
            Map<ModuleReference, ConcurrentHashMap<String, byte[]>> cache = new HashMap<>();
            for (var module : pathsByModule.entrySet()) {
                List<String> paths = module.getValue();
                var entries = new ConcurrentHashMap<String, byte[]>(paths.size());
                cache.put(module.getKey(), entries);
                for (int from = 0; from < paths.size(); from += 32) {
                    batches.add(new MixinReadBatch(module.getKey(), paths.subList(from, Math.min(paths.size(), from + 32)), entries));
                }
            }
            parallelForEach(batches, obj -> {
                MixinReadBatch batch = (MixinReadBatch) obj;
                try (ModuleReader reader = batch.module().open()) {
                    for (String path : batch.paths()) {
                        byte[] raw = readClassBytesRaw(reader, path);
                        if (raw != null && raw.length > 0) {
                            batch.entries().putIfAbsent(path, raw);
                        }
                    }
                } catch (Throwable ignored) {
                    // Unread entries are handled on demand by the normal class reader.
                }
            });
            if (TRACE_BOOT_MIXINS) prefetchedMixinCount = cache.values().stream().mapToInt(Map::size).sum();
            prewarmedMixinBytes = cache;
        } catch (Throwable error) {
            if (TRACE_BOOT_MIXINS) System.err.println("[SunlitBootAgent] Mixin prefetch fallback: " + error);
        }
    }

    private record MixinReadBatch(ModuleReference module, List<String> paths, ConcurrentHashMap<String, byte[]> entries) {}

    public static void clearPrewarmedClassBytes() {
        if (TRACE_BOOT_MIXINS) {
            int untouched = prewarmedMixinBytes.values().stream().mapToInt(Map::size).sum();
            System.err.println("[SunlitBootAgent] Mixin prefetch: used " + (prefetchedMixinCount - untouched)
                    + " of " + prefetchedMixinCount + " classes");
            prefetchedMixinCount = 0;
        }
        prewarmedMixinBytes = Map.of();
    }

    private static void collectMixinFullNames(
            Object listObj, String prefix, Set<String> seenPaths, List<String> out) {
        if (!(listObj instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        for (int i = 0, n = list.size(); i < n; i++) {
            Object item = list.get(i);
            if (!(item instanceof String rel) || rel.isEmpty()) {
                continue;
            }
            String fullName = prefix + rel;
            String path = fullName.replace('.', '/').concat(".class");
            if (seenPaths.add(path)) {
                out.add(fullName);
            }
        }
    }

    private static Field getDeclaredFieldQuiet(Class<?> cls, String name) {
        try {
            return cls.getDeclaredField(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object findDeclaredFieldValue(Class<?> startCls, Object target, String name) {
        for (Class<?> c = startCls; c != null; c = c.getSuperclass()) {
            Field f = getDeclaredFieldQuiet(c, name);
            if (f != null) {
                return readFieldValue(f, target);
            }
        }
        return null;
    }

    private static Object readDeclaredFieldValue(Class<?> cls, Object target, String name) {
        return readFieldValue(getDeclaredFieldQuiet(cls, name), target);
    }

    private static Object readFieldValue(Field f, Object target) {
        if (f == null || target == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (IllegalAccessException e) {
            try {
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final ConcurrentHashMap<Class<?>, java.lang.reflect.Method[]> RHINO_DECLARED_METHODS =
            new ConcurrentHashMap<>(512);

    /** Caches {@link Class#getDeclaredMethods()} for Rhino reflection to avoid repeated native Method cloning. */
    public static java.lang.reflect.Method[] fastGetDeclaredMethods(Class<?> cls) {
        java.lang.reflect.Method[] cached = RHINO_DECLARED_METHODS.get(cls);
        if (cached != null) {
            return cached;
        }
        java.lang.reflect.Method[] methods = cls.getDeclaredMethods();
        if (RHINO_DECLARED_METHODS.size() < 4096) {
            java.lang.reflect.Method[] prev = RHINO_DECLARED_METHODS.putIfAbsent(cls, methods);
            if (prev != null) {
                return prev;
            }
        }
        return methods;
    }

    private static volatile java.lang.reflect.Method PAIR_FIRST_METHOD;
    private static volatile java.lang.reflect.Method PAIR_SECOND_METHOD;
    private static volatile boolean PAIR_REFLECTION_FAILED;

    /**
     * Fast precheck for Blueprint's {@code RemoldedResourceManager.getResourceFunction}: inspects
     * extension buckets, predicate lists, and exact-match maps before allocating {@code ResourceLocation.toString()}
     * and stripped {@code new ResourceLocation(...)}.
     */
    public static boolean canSkipRemoldedLookup(
            java.util.HashMap<?, ?> byExt, String ns, String path) {
        if (byExt == null || byExt.isEmpty() || ns == null || path == null || path.isEmpty()) {
            return true;
        }
        if (ns.indexOf('.') >= 0) {
            return false;
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= path.length()) {
            return true;
        }
        String ext;
        int extLength = path.length() - dot - 1;
        if (extLength == 4 && path.endsWith(".json")) {
            ext = "json";
        } else if (extLength == 3 && path.endsWith(".png")) {
            ext = "png";
        } else {
            ext = path.substring(dot + 1);
        }
        Object extObj = byExt.get(ext);
        if (!(extObj instanceof java.util.Map<?, ?> extMap) || extMap.isEmpty()) {
            return true;
        }
        if (PAIR_REFLECTION_FAILED) {
            return false;
        }
        try {
            java.lang.reflect.Method firstM = PAIR_FIRST_METHOD;
            java.lang.reflect.Method secondM = PAIR_SECOND_METHOD;
            String baseKey = null;
            for (Object pair : extMap.values()) {
                if (pair == null) {
                    continue;
                }
                if (firstM == null) {
                    Class<?> pc = pair.getClass();
                    firstM = pc.getMethod("getFirst");
                    secondM = pc.getMethod("getSecond");
                    PAIR_FIRST_METHOD = firstM;
                    PAIR_SECOND_METHOD = secondM;
                }
                Object second = secondM.invoke(pair);
                if (second instanceof java.util.Collection<?> c && !c.isEmpty()) {
                    return false;
                }
                Object first = firstM.invoke(pair);
                if (first instanceof java.util.Map<?, ?> exactMap && !exactMap.isEmpty()) {
                    if (baseKey == null) {
                        baseKey = new StringBuilder(ns.length() + 1 + dot)
                                .append(ns).append(':').append(path, 0, dot).toString();
                    }
                    if (exactMap.containsKey(baseKey)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable ignored) {
            PAIR_REFLECTION_FAILED = true;
            return false;
        }
    }

    /** Joins the names of a relativized path for vanilla's slash-separated resource IDs. */
    public static String fastJoinRelativePackPath(Path relative) {
        if (relative.getRoot() == null) {
            switch (relative.getClass().getName()) {
                case "jdk.nio.zipfs.ZipPath", "sun.nio.fs.UnixPath":
                    return relative.toString();
                case "sun.nio.fs.WindowsPath":
                    return relative.toString().replace('\\', '/');
                default:
                    break;
            }
        }
        var names = relative.iterator();
        if (!names.hasNext()) {
            return "";
        }
        String first = names.next().toString();
        if (!names.hasNext()) {
            return first;
        }
        StringBuilder result = new StringBuilder(first);
        do {
            result.append('/').append(names.next());
        } while (names.hasNext());
        return result.toString();
    }

    /** Replaces {@code UnionFileSystem.lambda$newDirStream$11} Stream/Path.iterator overhead. */
    public static String[] fastUnionDirStreamSegments(boolean embedded, Path basepath, Path entry) {
        Path target = embedded ? entry : basepath.relativize(entry);
        int n = target.getNameCount();
        if (n == 0) {
            return EMPTY_STRING_ARRAY;
        }
        String[] out = new String[n];
        if ("jdk.nio.zipfs.ZipPath".equals(target.getClass().getName())
                && splitZipPathNames(target.toString(), out)) {
            return out;
        }
        for (int i = 0; i < n; i++) {
            Path seg = target.getName(i);
            Path fn = seg.getFileName();
            out[i] = (fn != null ? fn : seg).toString();
        }
        return out;
    }

    private static boolean splitZipPathNames(String path, String[] out) {
        int start = path.startsWith("/") ? 1 : 0;
        for (int i = 0; i < out.length - 1; i++) {
            int end = path.indexOf('/', start);
            if (end <= start) {
                return false;
            }
            out[i] = path.substring(start, end);
            start = end + 1;
        }
        if (path.indexOf('/', start) >= 0 || (start > 0 && start == path.length())) {
            return false;
        }
        out[out.length - 1] = path.substring(start);
        return true;
    }

    /** Skips regex {@code Matcher} creation in {@code Language.loadFromJson} when string contains no {@code '%'}. */
    public static String fastLangFormatReplace(
            java.util.regex.Pattern pattern, CharSequence input, String replacement) {
        String s = input.toString();
        if (s.indexOf('%') < 0) {
            return s;
        }
        return pattern.matcher(s).replaceAll(replacement);
    }

    public static java.util.Map<?, ?> newResourceHashMap(int initialCapacity) {
        return new java.util.HashMap<>(initialCapacity);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static java.util.TreeMap buildTreeMapFromUnsorted(java.util.Map<?, ?> src) {
        if (src == null || src.isEmpty()) {
            return new java.util.TreeMap<>();
        }
        java.util.Map.Entry[] entries = src.entrySet().toArray(new java.util.Map.Entry[0]);
        if (entries.length > 1) {
            java.util.Comparator<Map.Entry> order = (a, b) -> ((Comparable) a.getKey()).compareTo(b.getKey());
            if (entries.length >= 16384 && isVanillaResourceLocationArray(entries)) {
                java.util.Arrays.parallelSort(entries, order);
            } else {
                java.util.Arrays.sort(entries, order);
            }
        }
        return new java.util.TreeMap(new SortedArrayMapView(entries));
    }

    private static boolean isVanillaResourceLocationArray(Map.Entry<?, ?>[] entries) {
        Object first = entries[0].getKey();
        if (first == null || !"net.minecraft.resources.ResourceLocation".equals(first.getClass().getName())) return false;
        for (Map.Entry<?, ?> entry : entries) {
            Object key = entry.getKey();
            if (key == null || key.getClass() != first.getClass()) return false;
        }
        return true;
    }

    private static final class SortedArrayMapView<K, V> extends java.util.AbstractMap<K, V>
            implements java.util.SortedMap<K, V> {
        private final java.util.Map.Entry<K, V>[] entries;
        private final java.util.Set<java.util.Map.Entry<K, V>> entrySetView;

        SortedArrayMapView(java.util.Map.Entry<K, V>[] entries) {
            this.entries = entries;
            this.entrySetView = new java.util.AbstractSet<>() {
                @Override
                public java.util.Iterator<java.util.Map.Entry<K, V>> iterator() {
                    return new java.util.Iterator<>() {
                        private int idx;

                        @Override
                        public boolean hasNext() {
                            return idx < entries.length;
                        }

                        @Override
                        public java.util.Map.Entry<K, V> next() {
                            return entries[idx++];
                        }
                    };
                }

                @Override
                public int size() {
                    return entries.length;
                }
            };
        }

        @Override
        public java.util.Set<java.util.Map.Entry<K, V>> entrySet() {
            return entrySetView;
        }

        @Override
        public int size() {
            return entries.length;
        }

        @Override
        public java.util.Comparator<? super K> comparator() {
            return null;
        }

        @Override
        public java.util.SortedMap<K, V> subMap(K fromKey, K toKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.SortedMap<K, V> headMap(K toKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.SortedMap<K, V> tailMap(K fromKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public K firstKey() {
            return entries[0].getKey();
        }

        @Override
        public K lastKey() {
            return entries[entries.length - 1].getKey();
        }
    }

    /**
     * Zero-allocation equivalent of {@code Pattern.compile("[A-Za-z0-9._+-]+").matcher(s).matches()}
     * used by {@code net.minecraft.nbt.StringTagVisitor.handleEscape}.
     */
    public static boolean isSimpleNbtKey(String s) {
        if (s == null) {
            return false;
        }
        int len = s.length();
        if (len == 0) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == '+')) {
                return false;
            }
        }
        return true;
    }

    private static volatile Object cachedEventBusRef;
    private static volatile int cachedEventBusId = -2;

    /**
     * Reflects and caches Forge {@code EventBus.busID} so hot event sites can check
     * {@code ListenerList.getListeners(busId).length == 0} without allocating an event object.
     */
    public static int getEventBusId(Object bus) {
        if (bus == null) {
            return -1;
        }
        if (bus == cachedEventBusRef) {
            return cachedEventBusId;
        }
        try {
            java.lang.reflect.Field f = bus.getClass().getDeclaredField("busID");
            f.setAccessible(true);
            int id = f.getInt(bus);
            cachedEventBusId = id;
            cachedEventBusRef = bus;
            return id;
        } catch (Throwable ignored) {
            cachedEventBusId = -1;
            cachedEventBusRef = bus;
            return -1;
        }
    }

    /**
     * Exact zero-intermediate-allocation equivalent of {@code sb.append(StringTag.quoteAndEscape(s))}.
     */
    public static void appendQuotedNbtString(StringBuilder sb, String s) {
        int startIdx = sb.length();
        sb.append(' ');
        char quote = 0;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                sb.append('\\');
            } else if (c == '"' || c == '\'') {
                if (quote == 0) {
                    quote = (c == '"') ? '\'' : '"';
                }
                if (quote == c) {
                    sb.append('\\');
                }
            }
            sb.append(c);
        }
        if (quote == 0) {
            quote = '"';
        }
        sb.setCharAt(startIdx, quote);
        sb.append(quote);
    }

    /**
     * Zero-allocation check for {@code (ns + ":" + path).equals(target)}.
     */
    public static boolean resourceLocationEqualsString(String ns, String path, String target) {
        if (target == null || ns == null || path == null) {
            return false;
        }
        int nsLen = ns.length();
        int pathLen = path.length();
        if (target.length() != nsLen + 1 + pathLen) {
            return false;
        }
        if (target.charAt(nsLen) != ':') {
            return false;
        }
        return target.regionMatches(0, ns, 0, nsLen)
                && target.regionMatches(nsLen + 1, path, 0, pathLen);
    }

    /**
     * Zero-copy prefix check for Iris {@code CommentDirectiveParser.findDirective}: locates the
     * last occurrence of {@code needle} and verifies the non-whitespace characters immediately
     * preceding it end with {@code "/*"} without copying or trimming the prefix.
     */
    public static int findCommentDirectiveIdx(String haystack, String needle) {
        if (haystack == null || needle == null) {
            return -1;
        }
        int idx = haystack.lastIndexOf(needle);
        if (idx < 2) {
            return -1;
        }
        int p = idx - 1;
        while (p >= 0 && haystack.charAt(p) <= ' ') {
            p--;
        }
        if (p < 1 || haystack.charAt(p) != '*' || haystack.charAt(p - 1) != '/') {
            return -1;
        }
        return idx;
    }

    /**
     * Zero-copy suffix extractor for Iris {@code CommentDirectiveParser.findDirective}: scans
     * forward from {@code valueStart} to {@code "*\/"} and returns the trimmed directive value
     * (or {@code null} if no closing marker exists) without copying the full shader tail.
     */
    public static String extractCommentDirectiveValue(String haystack, int valueStart) {
        int end = haystack.indexOf("*/", valueStart);
        if (end < 0) {
            return null;
        }
        int lo = valueStart;
        int hi = end;
        while (lo < hi && haystack.charAt(lo) <= ' ') {
            lo++;
        }
        while (hi > lo && haystack.charAt(hi - 1) <= ' ') {
            hi--;
        }
        return haystack.substring(lo, hi);
    }

    /**
     * Zero-regex line extractor for Iris {@code ConstDirectiveParser.findDirectives}: scans lines
     * without {@code String.split("\\R")} and only materializes lines containing {@code "const"},
     * {@code '='}, and {@code ';'} (~302MB garbage eliminated during shaderpack load).
     */
    public static java.util.List<String> extractConstCandidateLines(String source) {
        if (source == null || source.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        int len = source.length();
        java.util.ArrayList<String> out = null;
        int pos = 0;
        while (pos < len) {
            int cIdx = source.indexOf("const", pos);
            if (cIdx < 0) {
                break;
            }
            // Find line start (after previous \n or \r)
            int lineStart = cIdx;
            while (lineStart > 0) {
                char ch = source.charAt(lineStart - 1);
                if (ch == '\n' || ch == '\r') {
                    break;
                }
                lineStart--;
            }
            // Find line end (before next \n or \r)
            int lineEnd = cIdx + 5;
            boolean hasEq = false;
            boolean hasSemi = false;
            while (lineEnd < len) {
                char ch = source.charAt(lineEnd);
                if (ch == '\n' || ch == '\r') {
                    break;
                }
                if (ch == '=') {
                    hasEq = true;
                } else if (ch == ';') {
                    hasSemi = true;
                }
                lineEnd++;
            }
            if (!hasEq || !hasSemi) {
                for (int k = lineStart; k < cIdx; k++) {
                    char ch = source.charAt(k);
                    if (ch == '=') hasEq = true;
                    else if (ch == ';') hasSemi = true;
                }
            }
            if (hasEq && hasSemi) {
                if (out == null) {
                    out = new java.util.ArrayList<>(8);
                }
                out.add(source.substring(lineStart, lineEnd));
            }
            pos = lineEnd + 1;
            if (pos < len && source.charAt(lineEnd) == '\r' && source.charAt(pos) == '\n') {
                pos++;
            }
        }
        return out == null ? java.util.Collections.emptyList() : out;
    }

    /**
     * Vectorized {@code indexOf}-based equivalent of Flywheel {@code StringUtil.countLines(String)}.
     */
    public static int fastCountLines(String s) {
        int len = s.length();
        if (len == 0) {
            return 1;
        }
        int count = 1;
        if (s.indexOf('\r') < 0) {
            int pos = 0;
            while ((pos = s.indexOf('\n', pos)) >= 0) {
                count++;
                pos++;
            }
            return count;
        }
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                count++;
            } else if (c == '\r') {
                count++;
                if (i + 1 < len && s.charAt(i + 1) == '\n') {
                    i++;
                }
            }
        }
        return count;
    }

    /**
     * Zero-regex equivalent of {@code s.replaceAll("_", "")} for Create {@code TooltipHelper}.
     */
    public static String stripUnderscores(String s) {
        if (s == null) {
            return null;
        }
        int first = s.indexOf('_');
        if (first < 0) {
            return s;
        }
        int len = s.length();
        StringBuilder sb = new StringBuilder(len - 1);
        sb.append(s, 0, first);
        for (int i = first + 1; i < len; i++) {
            char c = s.charAt(i);
            if (c != '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final class CachedBreakIter {
        java.util.Locale locale;
        java.text.BreakIterator iter;
    }

    private static final ThreadLocal<CachedBreakIter> LINE_BREAK_CACHE =
            ThreadLocal.withInitial(CachedBreakIter::new);

    /**
     * Reuses a per-thread {@code BreakIterator.getLineInstance(locale)} instead of cloning
     * {@code RuleBasedBreakIterator} on every Create tooltip line (~46MB saved in EMI bake).
     */
    public static java.text.BreakIterator getCachedLineBreakIterator(java.util.Locale locale) {
        CachedBreakIter holder = LINE_BREAK_CACHE.get();
        if (holder.iter == null || !java.util.Objects.equals(holder.locale, locale)) {
            holder.locale = locale;
            holder.iter = java.text.BreakIterator.getLineInstance(locale);
        }
        return holder.iter;
    }

    /**
     * Zero-stream replacement for Colorwheel {@code ProgramSourceMixin.colorwheel$parseShaderExtensions}.
     * Scans only {@code "#extension"} occurrences instead of splitting and trimming every shader line (~125MB saved).
     */
    public static java.util.List<String> parseColorwheelShaderExtensions(String source) {
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        if (source == null || source.isEmpty()) {
            return list;
        }
        int len = source.length();
        int pos = 0;
        while ((pos = source.indexOf("#extension", pos)) >= 0) {
            boolean lineStartOk = true;
            for (int k = pos - 1; k >= 0; k--) {
                char c = source.charAt(k);
                if (c == '\n' || c == '\r') {
                    break;
                }
                if (c > ' ') {
                    lineStartOk = false;
                    break;
                }
            }
            if (!lineStartOk) {
                pos += 10;
                continue;
            }
            int lineEnd = pos + 10;
            while (lineEnd < len) {
                char c = source.charAt(lineEnd);
                if (c == '\n' || c == '\r') {
                    break;
                }
                lineEnd++;
            }
            int trimEnd = lineEnd;
            while (trimEnd > pos && source.charAt(trimEnd - 1) <= ' ') {
                trimEnd--;
            }
            int colon = source.indexOf(':', pos);
            if (colon >= 0 && colon < trimEnd) {
                int subStart = pos + 11;
                if (subStart >= trimEnd) {
                    list.add("");
                } else {
                    int nextColon = source.indexOf(':', subStart);
                    int partEnd = (nextColon >= 0 && nextColon < trimEnd) ? nextColon : trimEnd;
                    int s = subStart;
                    int e = partEnd;
                    while (s < e && source.charAt(s) <= ' ') {
                        s++;
                    }
                    while (e > s && source.charAt(e - 1) <= ' ') {
                        e--;
                    }
                    list.add(source.substring(s, e));
                }
            }
            pos = lineEnd;
        }
        return list;
    }

    private static volatile Object jemiNoNbtSubtypeMgr;
    private static volatile java.util.concurrent.ConcurrentHashMap<Object, String> jemiNoNbtSubtypeCache;

    /**
     * Caches JEI {@code UidContext.Recipe} subtype strings for NBT-less {@code ItemEmiStack}s
     * across {@code JemiPlugin.lambda$parseSubtypes$15}, keyed by {@code Item} and automatically
     * reset whenever {@code JemiPlugin.subtypeManager} changes on reload.
     */
    public static String getCachedNoNbtSubtype(Object subtypeManager, Object itemKey) {
        if (subtypeManager == null || itemKey == null) {
            return null;
        }
        if (jemiNoNbtSubtypeMgr != subtypeManager) {
            return null;
        }
        java.util.concurrent.ConcurrentHashMap<Object, String> map = jemiNoNbtSubtypeCache;
        return map != null ? map.get(itemKey) : null;
    }

    public static void putCachedNoNbtSubtype(Object subtypeManager, Object itemKey, String value) {
        if (subtypeManager == null || itemKey == null || value == null) {
            return;
        }
        java.util.concurrent.ConcurrentHashMap<Object, String> map = jemiNoNbtSubtypeCache;
        if (jemiNoNbtSubtypeMgr != subtypeManager || map == null) {
            synchronized (BootJarPayload.class) {
                if (jemiNoNbtSubtypeMgr != subtypeManager || jemiNoNbtSubtypeCache == null) {
                    jemiNoNbtSubtypeCache = new java.util.concurrent.ConcurrentHashMap<>(256);
                    jemiNoNbtSubtypeMgr = subtypeManager;
                }
                map = jemiNoNbtSubtypeCache;
            }
        }
        map.put(itemKey, value);
    }

    private static final Object JM_CHUNK_CACHE_LOCK = new Object();
    private static final long[] JM_CHUNK_KEYS = new long[16];
    private static final Object[] JM_CHUNK_VALS = new Object[16];
    private static int jmChunkCacheHits;

    /**
     * 16-slot direct-mapped hot cache in front of JourneyMap {@code DataCache.chunkMetadata} Guava
     * cache. Bypasses {@code LocalCache$Segment.recordRead} (~59MB {@code ConcurrentLinkedQueue$Node}
     * churn) on 63/64 hits while still refreshing Guava's access TTL every 64th hit.
     */
    public static Object jmChunkCacheGet(long coord) {
        synchronized (JM_CHUNK_CACHE_LOCK) {
            int slot = ((int) coord ^ (int) (coord >>> 32)) & 15;
            Object val = JM_CHUNK_VALS[slot];
            if (val != null && JM_CHUNK_KEYS[slot] == coord) {
                if ((++jmChunkCacheHits & 63) != 0) {
                    return val;
                }
            }
            return null;
        }
    }

    public static void jmChunkCachePut(long coord, Object md) {
        if (md == null) {
            return;
        }
        synchronized (JM_CHUNK_CACHE_LOCK) {
            int slot = ((int) coord ^ (int) (coord >>> 32)) & 15;
            JM_CHUNK_KEYS[slot] = coord;
            JM_CHUNK_VALS[slot] = md;
        }
    }

    public static void jmChunkCacheRemove(long coord) {
        synchronized (JM_CHUNK_CACHE_LOCK) {
            int slot = ((int) coord ^ (int) (coord >>> 32)) & 15;
            if (JM_CHUNK_KEYS[slot] == coord) {
                JM_CHUNK_VALS[slot] = null;
            }
        }
    }

    public static void jmChunkCacheClear() {
        synchronized (JM_CHUNK_CACHE_LOCK) {
            java.util.Arrays.fill(JM_CHUNK_VALS, null);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<Object, java.util.List<?>> EMI_TAG_VALUES_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>(512);

    /**
     * Caches unmodifiable {@code EmiTags.getValues(EmiTagKey)} stack lists across an EMI reload
     * (~376MB {@code ItemEmiStack} churn eliminated). Cleared on {@code EmiTags.reload()}.
     */
    public static java.util.List<?> getEmiTagValues(Object key) {
        return key != null ? EMI_TAG_VALUES_CACHE.get(key) : null;
    }

    public static void putEmiTagValues(Object key, java.util.List<?> values) {
        if (key != null && values != null) {
            EMI_TAG_VALUES_CACHE.put(key, values);
        }
    }

    public static void clearEmiTagValues() {
        EMI_TAG_VALUES_CACHE.clear();
    }

    /**
     * Zero-iterator replacement for {@code map.keySet().containsAll(list)} in {@code EmiTags.getIngredient}
     * with O(1) size pre-check (~66MB {@code ListItr} churn eliminated).
     */
    public static boolean mapContainsAllList(java.util.Map<?, ?> map, java.util.List<?> list) {
        int n = list.size();
        if (n > map.size()) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (!map.containsKey(list.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static final java.util.concurrent.ConcurrentHashMap<Object, Object> ITEM_CRAFTING_REMAINDER_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>(1024);

    /**
     * Caches item-level crafting remainders for NBT-less {@code ItemEmiStack}s in
     * {@code EmiShapedRecipe.setRemainders} (~293MB {@code ItemStack}/{@code HashMap} churn eliminated).
     */
    public static Object getCachedItemRemainder(Object item) {
        return item != null ? ITEM_CRAFTING_REMAINDER_CACHE.get(item) : null;
    }

    public static void putCachedItemRemainder(Object item, Object remainderStack) {
        if (item != null && remainderStack != null) {
            ITEM_CRAFTING_REMAINDER_CACHE.put(item, remainderStack);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<Object, Object[]> ENCHANTED_BOOK_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>(128);

    /**
     * Caches {@code EmiAnvilEnchantRecipe.getBook()} results by {@code (Enchantment, level)}
     * (~109MB {@code CompoundTag} churn eliminated across 24,000+ anvil recipes).
     */
    public static Object getCachedEnchantedBook(Object enchantment, int level) {
        if (enchantment == null || level < 0 || level >= 16) {
            return null;
        }
        Object[] arr = ENCHANTED_BOOK_CACHE.get(enchantment);
        return arr != null ? arr[level] : null;
    }

    public static void putCachedEnchantedBook(Object enchantment, int level, Object bookStack) {
        if (enchantment == null || level < 0 || level >= 16 || bookStack == null) {
            return;
        }
        Object[] arr = ENCHANTED_BOOK_CACHE.computeIfAbsent(enchantment, k -> new Object[16]);
        arr[level] = bookStack;
    }

    private static final int FAST_INT_MIN = -128;
    private static final int FAST_INT_MAX = 383;
    private static final String[] FAST_INT_STRINGS = buildFastIntStrings();

    private static String[] buildFastIntStrings() {
        String[] arr = new String[FAST_INT_MAX - FAST_INT_MIN + 1];
        for (int i = FAST_INT_MIN; i <= FAST_INT_MAX; i++) {
            arr[i - FAST_INT_MIN] = Integer.toString(i);
        }
        return arr;
    }

    /**
     * Zero-allocation {@code String.valueOf(int)} for common Minecraft Y coordinates (-128..383),
     * eliminating ~70MB of {@code Integer.toString} churn in JourneyMap {@code RegionData.setBlockState}.
     */
    public static String fastIntToString(int value) {
        if (value >= FAST_INT_MIN && value <= FAST_INT_MAX) {
            return FAST_INT_STRINGS[value - FAST_INT_MIN];
        }
        return Integer.toString(value);
    }

    private static final class WrappedRtEntry {
        final String name;
        final Object wrapped;
        final Object extra;
        final Object result;

        WrappedRtEntry(String name, Object wrapped, Object extra, Object result) {
            this.name = name;
            this.wrapped = wrapped;
            this.extra = extra;
            this.result = result;
        }
    }

    private static final WrappedRtEntry[] WRAPPED_RT_CACHE = new WrappedRtEntry[256];

    private static int wrappedRtSlot(String name, Object wrapped, Object extra) {
        int h = System.identityHashCode(wrapped) * 31 + System.identityHashCode(extra);
        h ^= (name != null ? name.hashCode() : 0);
        return (h ^ (h >>> 16)) & 255;
    }

    /**
     * Bounded lock-free direct-mapped cache for Iris {@code OuterWrappedRenderType.wrapExactlyOnce}
     * (~35MB per 10s Render-thread garbage eliminated permanently during entity rendering).
     */
    public static Object getCachedWrappedRenderType(String name, Object wrapped, Object extra) {
        if (name == null || wrapped == null || extra == null) {
            return null;
        }
        WrappedRtEntry e = WRAPPED_RT_CACHE[wrappedRtSlot(name, wrapped, extra)];
        if (e != null && e.wrapped == wrapped && e.extra == extra && name.equals(e.name)) {
            return e.result;
        }
        return null;
    }

    public static void putCachedWrappedRenderType(String name, Object wrapped, Object extra, Object result) {
        if (name == null || wrapped == null || extra == null || result == null) {
            return;
        }
        WRAPPED_RT_CACHE[wrappedRtSlot(name, wrapped, extra)] = new WrappedRtEntry(name, wrapped, extra, result);
    }

    /**
     * Bulk scanline {@code BufferedImage.getRGB} reader for FTB Chunks {@code MapRegionData.read()}.
     * Replaces 1.57M per-pixel {@code getRGB(x, y)} calls (~120MB {@code byte[4]} churn) with bulk reads.
     * Returns {@code 1} if the caller must call {@code region.markDirty()}, {@code 0} if clean, or
     * {@code -1} if any image is null/unexpected size (caller falls back to original loop).
     */
    public static int readFtbRegionImages(
            Object dataObj,
            Object foliageObj,
            Object grassObj,
            Object waterObj,
            Object blocksObj,
            short[] height,
            short[] waterLightAndBiome,
            int[] foliage,
            int[] grass,
            int[] water,
            int version) {
        if (!(dataObj instanceof java.awt.image.BufferedImage dataImg)
                || !(foliageObj instanceof java.awt.image.BufferedImage foliageImg)
                || !(grassObj instanceof java.awt.image.BufferedImage grassImg)
                || !(waterObj instanceof java.awt.image.BufferedImage waterImg)
                || !(blocksObj instanceof java.awt.image.BufferedImage blocksImg)) {
            return -1;
        }
        if (dataImg.getWidth() < 512 || dataImg.getHeight() < 512
                || foliageImg.getWidth() < 512 || foliageImg.getHeight() < 512
                || grassImg.getWidth() < 512 || grassImg.getHeight() < 512
                || waterImg.getWidth() < 512 || waterImg.getHeight() < 512
                || blocksImg.getWidth() < 512 || blocksImg.getHeight() < 512) {
            return -1;
        }
        foliageImg.getRGB(0, 0, 512, 512, foliage, 0, 512);
        grassImg.getRGB(0, 0, 512, 512, grass, 0, 512);
        waterImg.getRGB(0, 0, 512, 512, water, 0, 512);
        int[] row = new int[512];
        boolean dirty = false;
        boolean oldVer = version < 3;
        for (int y = 0; y < 512; y++) {
            int base = y << 9;
            dataImg.getRGB(0, y, 512, 1, row, 0, 512);
            for (int x = 0; x < 512; x++) {
                int idx = base + x;
                int rgb = row[x];
                short h = (short) ((rgb >> 16) & 0xFFFF);
                if (oldVer && h == 0) {
                    h = (short) -32767;
                    dirty = true;
                }
                height[idx] = h;
                waterLightAndBiome[idx] = (short) (rgb & 0xFFFF);
            }
            blocksImg.getRGB(0, y, 512, 1, row, 0, 512);
            for (int x = 0; x < 512; x++) {
                int idx = base + x;
                int b = row[x];
                foliage[idx] = (foliage[idx] & 0xFFFFFF) | (((b >> 16) & 0xFF) << 24);
                grass[idx] = (grass[idx] & 0xFFFFFF) | (((b >> 8) & 0xFF) << 24);
                water[idx] = (water[idx] & 0xFFFFFF) | ((b & 0xFF) << 24);
            }
        }
        return dirty ? 1 : 0;
    }

    /**
     * Zero-allocation bit-exact replacement for JourneyMap {@code RGB.multiply(int, int)}
     * (~28MB {@code float[3]} churn eliminated during map rendering).
     */
    public static int fastRgbMultiply(int c1, int c2) {
        float r = (((c1 >> 16) & 255) / 255.0f) * (((c2 >> 16) & 255) / 255.0f);
        float g = (((c1 >> 8) & 255) / 255.0f) * (((c2 >> 8) & 255) / 255.0f);
        float b = ((c1 & 255) / 255.0f) * ((c2 & 255) / 255.0f);
        return 0xFF000000
                | ((((int) ((double) (r * 255.0f) + 0.5d)) & 255) << 16)
                | ((((int) ((double) (g * 255.0f) + 0.5d)) & 255) << 8)
                | (((int) ((double) (b * 255.0f) + 0.5d)) & 255);
    }

    private static final class SubIdEntry {
        final Object key;
        final String value;

        SubIdEntry(Object key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final SubIdEntry[] EMI_SUB_ID_CACHE = new SubIdEntry[2048];

    /**
     * Bounded lock-free cache for {@code EmiUtil.subId(ResourceLocation)} ("/" + ns + "/" + path),
     * eliminating ~41MB of repeated {@code StringConcatHelper} allocations during EMI/JEMI reload.
     */
    public static String fastEmiSubId(Object rl, String ns, String path) {
        if (rl == null) {
            return "/" + ns + "/" + path;
        }
        int h = System.identityHashCode(rl);
        int slot = (h ^ (h >>> 11)) & 2047;
        SubIdEntry e = EMI_SUB_ID_CACHE[slot];
        if (e != null && e.key == rl) {
            return e.value;
        }
        String val = "/" + ns + "/" + path;
        EMI_SUB_ID_CACHE[slot] = new SubIdEntry(rl, val);
        return val;
    }
}
