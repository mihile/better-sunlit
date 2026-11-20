package dev.sunlit.bootagent.payload;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.module.ModuleReader;
import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
    private static final String[] LPT_HEAVY_MOD_IDS = {
            "kubejs", "easy_npc", "create", "watermedia", "servercore", "wildernature",
            "quark", "supplementaries", "everycomp", "emi", "ribbits", "ftbquests",
            "sophisticatedcore", "sophisticatedstorage", "sophisticatedbackpacks",
            "fancymenu", "domesticationinnovation", "entity_texture_features",
            "modernui", "botania", "oculus", "alexsmobs", "jei", "farmersdelight",
            "voxy", "cataclysm", "mowziesmobs", "railways", "refinedstorage", "ftbchunks",
            "waystones", "journeymap", "relics", "numismatics", "createrailwaysnavigator",
            "architectury", "moonlight", "zeta", "puzzleslib", "blueprint", "citadel",
            "kotlinforforge", "geckolib", "curios", "bookshelf", "placebo", "framework",
            "resourcefullib", "resourcefulconfig", "cloth_config", "balm", "coroutil",
            "creativecore", "octolib", "supermartijn642corelib", "supermartijn642configlib",
            "ftblibrary", "ftbteams", "itemfilters", "polylib", "rhino",
            "apexcore", "commonality", "doapi", "pandalib", "smartbrainlib",
            "kambrik", "konkrete", "melody", "yungsapi", "paraglider",
            "fantasyfurniture", "create_central_kitchen", "viaromana", "cluttered",
            "biomesoplenty", "meadow", "vinery", "brewery", "nethervinery",
            "simplehats", "gag", "artifacts", "aquaculture", "atmospheric", "autumnity",
            "environmental", "upgrade_aquatic", "neapolitan", "buzzier_bees",
            "caverns_and_chasms", "endergetic", "savage_and_ravage", "windswept",
            "woodworks", "clayworks", "dramaticdoors", "chipped", "handcrafted",
            "furniture", "amendments", "immersive_aircraft", "smallships", "carryon",
            "corpse", "jade", "kiwi", "railway_depot", "createnumismatics", "puffish_skills",
            "exposure", "structurify", "trofers", "oreganized", "snowrealmagic",
            "sereneseasons", "toughasnails", "stardew_fishing", "aquamirae", "obscure_api"
    };

    public static Map buildLptIndexedMods(Map raw) {
        if (raw == null || raw.size() <= 1) {
            return raw;
        }
        LinkedHashMap ordered = new LinkedHashMap(raw.size() * 4 / 3 + 1);
        for (int i = 0; i < LPT_HEAVY_MOD_IDS.length; i++) {
            String key = LPT_HEAVY_MOD_IDS[i];
            Object val = raw.get(key);
            if (val != null) {
                ordered.put(key, val);
            }
        }
        Iterator it = raw.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry e = (Map.Entry) it.next();
            ordered.putIfAbsent(e.getKey(), e.getValue());
        }
        return ordered;
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
        try (Stream<Path> walk = Files.walk(modsDir, 1)) {
            for (Object item : walk.toArray()) {
                Path p = (Path) item;
                if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                    try {
                        if (Files.size(p) > 0L) {
                            candidates.add(p);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            return List.of();
        }
        int count = candidates.size();
        if (count == 0) {
            return List.of();
        }
        if (count <= 4) {
            List<Path> matched = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Path p = candidates.get(i);
                if (mightHaveDiscoveryService(p)) {
                    matched.add(p);
                }
            }
            return matched;
        }
        boolean[] keep = new boolean[count];
        int workers = Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "Sunlit-JarDiscover");
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
                        keep[i] = mightHaveDiscoveryService(candidates.get(i));
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
        int workers = Math.min(2, Math.max(1, Runtime.getRuntime().availableProcessors() / 4));
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
                && !name.startsWith("net.minecraft.world.level.chunk.")
                && !name.startsWith("net.minecraft.world.level.biome.")
                && !name.startsWith("net.minecraft.world.entity.ai.")
                && !name.startsWith("net.minecraft.world.entity.monster.")
                && !name.startsWith("net.minecraft.world.entity.animal.")
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

    public static byte[] fastGetClassBytes(ModuleReader reader, String className) {
        if (reader == null || className == null) {
            return EMPTY_BYTE_ARRAY;
        }
        String path = className.replace('.', '/').concat(".class");
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
            new ConcurrentHashMap<>(128);

    public static java.util.regex.Pattern fastCompilePattern(String regex) {
        java.util.regex.Pattern cached = PATTERN_CACHE.get(regex);
        if (cached != null) {
            return cached;
        }
        java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(regex);
        if (PATTERN_CACHE.size() < 512) {
            java.util.regex.Pattern prev = PATTERN_CACHE.putIfAbsent(regex, compiled);
            if (prev != null) {
                return prev;
            }
        }
        return compiled;
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
            if (size >= 512 && (size & (size - 1)) == 0 && list instanceof ArrayList al) {
                al.ensureCapacity(Math.max(size << 1, 8192));
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
    public static java.lang.invoke.MethodHandle initFlatStateMapCtor(java.lang.invoke.MethodHandles.Lookup lookup) {
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
            return raw.asType(java.lang.invoke.MethodType.methodType(imCls, Object[].class, Object[].class));
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
}
