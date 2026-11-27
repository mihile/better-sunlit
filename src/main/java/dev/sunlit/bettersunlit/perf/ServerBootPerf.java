package dev.sunlit.bettersunlit.perf;

import dev.latvian.mods.kubejs.script.data.GeneratedData;
import dev.latvian.mods.kubejs.script.data.GeneratedResourcePack;
import dev.latvian.mods.kubejs.server.tag.TagEventFilter;
import dev.latvian.mods.kubejs.server.tag.TagEventJS;
import dev.latvian.mods.kubejs.server.tag.TagWrapper;
import dev.latvian.mods.kubejs.util.ConsoleJS;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.tags.TagEntry;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shared client+dedicated-server boot optimizations:
 * <ul>
 *   <li>Skips throwaway OSHI hardware probing during {@code CrashReport.preload()}.</li>
 *   <li>Indexed namespace+directory lookup and async file validation for KubeJS {@code GeneratedResourcePack}.</li>
 *   <li>Inverted element index for KubeJS {@code TagEventJS.removeAllTagsFrom}.</li>
 *   <li>Cached item lists per {@code ToolAction} for Farmer's Delight {@code ToolActionIngredient}.</li>
 *   <li>Fast literal/prefix/suffix/wildcard matcher for LootJS {@code ResourceLocationFilter$ByPattern}.</li>
 *   <li>Read-scoped dense ID cache for AdvancedLootInfo {@code AbstractServer.predicateItem}.</li>
 * </ul>
 */
public final class ServerBootPerf {
    private static final ThreadLocal<Integer> CRASH_PRELOAD_DEPTH = ThreadLocal.withInitial(() -> 0);

    // KubeJS GeneratedResourcePack index (used when client PerfKubeJsPackMixin is absent, e.g. dedicated server)
    private static final Object KJS_INDEX_LOCK = new Object();
    private static volatile PackEntry[] KJS_INDEX_ENTRIES = new PackEntry[0];

    // Farmer's Delight ToolActionIngredient cache keyed by ToolAction + registry size guard
    private static final ConcurrentHashMap<ToolAction, CachedToolItems> TOOL_ACTION_CACHE = new ConcurrentHashMap<>(16);

    // LootJS ByPattern fast matcher cache keyed by (pattern string + flags)
    private static final int MAX_FAST_RL_MATCHERS = 4096;
    private static final ConcurrentHashMap<String, FastRlMatcher> FAST_RL_MATCHERS = new ConcurrentHashMap<>(64);

    // AdvancedLootInfo AbstractServer.readLootTables scoped cache (0 bytes retained after readLootTables exits)
    private static final ThreadLocal<AliReadContext> ALI_READ_CTX = new ThreadLocal<>();
    private static volatile MethodHandle aliGetOperationsMh;
    private static volatile MethodHandle aliOpPredicateMh;
    private static volatile boolean aliHandlesResolved;

    private ServerBootPerf() {
    }

    // ---- 1. CrashReport.preload() hardware probe skip ----

    public static void enterCrashPreload() {
        CRASH_PRELOAD_DEPTH.set(CRASH_PRELOAD_DEPTH.get() + 1);
    }

    public static void exitCrashPreload() {
        int next = CRASH_PRELOAD_DEPTH.get() - 1;
        if (next <= 0) {
            CRASH_PRELOAD_DEPTH.remove();
        } else {
            CRASH_PRELOAD_DEPTH.set(next);
        }
    }

    public static boolean isInCrashPreload() {
        return CRASH_PRELOAD_DEPTH.get() > 0;
    }

    // ---- 2. KubeJS GeneratedResourcePack indexed listResources & async scan ----

    public static void listGeneratedResources(
            GeneratedResourcePack pack,
            PackType expectedType,
            PackType requestedType,
            String namespace,
            String path,
            PackResources.ResourceOutput output) {
        if (pack == null || requestedType != expectedType || namespace == null || path == null || output == null) {
            return;
        }
        Map<ResourceLocation, GeneratedData> generated = pack.getGenerated();
        listGeneratedResourcesMap(generated, namespace, path, output);
    }

    public static void listGeneratedResourcesMap(
            Map<ResourceLocation, GeneratedData> generated,
            String namespace,
            String path,
            PackResources.ResourceOutput output) {
        if (generated == null || generated.isEmpty() || namespace == null || path == null || output == null) {
            return;
        }
        String prefix = path.endsWith("/") ? path : path + "/";
        PackIndex index = getOrBuildKjsIndex(generated);
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

    public static void scanForInvalidFilesAsync(String pathName, Path path) {
        if (path == null) {
            return;
        }
        ForkJoinPool.commonPool().execute(() -> {
            try {
                boolean rootHidden = Files.isHidden(path);
                try (DirectoryStream<Path> topDirs = Files.newDirectoryStream(path, Files::isDirectory)) {
                    for (Path subDir : topDirs) {
                        Files.walkFileTree(subDir, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) {
                                if (attrs.isRegularFile()) {
                                    validateKjsFile(pathName, path, rootHidden, p);
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                                return FileVisitResult.CONTINUE;
                            }
                        });
                    }
                }
            } catch (IOException e) {
                ConsoleJS.STARTUP.error("Failed to scan for invalid files in " + pathName, e);
            }
        });
    }

    private static void validateKjsFile(String pathName, Path root, boolean rootHidden, Path p) {
        try {
            String fileName = p.getFileName().toString();
            String lc = fileName.toLowerCase(Locale.ROOT);
            if (lc.endsWith(".zip") || lc.equals(".ds_store") || lc.equals("thumbs.db") || lc.equals("desktop.ini")) {
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

    private static PackIndex getOrBuildKjsIndex(Map<ResourceLocation, GeneratedData> generated) {
        int size = generated.size();
        PackEntry[] entries = KJS_INDEX_ENTRIES;
        for (int i = 0; i < entries.length; i++) {
            PackEntry e = entries[i];
            if (e.mapRef.get() == generated && e.index.size == size) {
                return e.index;
            }
        }
        synchronized (KJS_INDEX_LOCK) {
            entries = KJS_INDEX_ENTRIES;
            for (int i = 0; i < entries.length; i++) {
                PackEntry e = entries[i];
                if (e.mapRef.get() == generated && e.index.size == size) {
                    return e.index;
                }
            }
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
            PackIndex built = new PackIndex(size, byNs);
            List<PackEntry> updated = new ArrayList<>(entries.length + 1);
            for (PackEntry e : entries) {
                Map<ResourceLocation, GeneratedData> ref = e.mapRef.get();
                if (ref != null && ref != generated) {
                    updated.add(e);
                }
            }
            updated.add(new PackEntry(new WeakReference<>(generated), built));
            KJS_INDEX_ENTRIES = updated.toArray(new PackEntry[0]);
            return built;
        }
    }

    // ---- 3. KubeJS TagEventJS.removeAllTagsFrom inverted element index ----

    private static volatile MethodHandle tagEntryIdMh;
    private static volatile MethodHandle tagEntryIsTagMh;
    private static volatile MethodHandle tagEntryLocMh;
    private static volatile boolean tagEntryHandlesResolved;

    private static void ensureTagEntryHandles() {
        if (tagEntryHandlesResolved) {
            return;
        }
        synchronized (ServerBootPerf.class) {
            if (tagEntryHandlesResolved) {
                return;
            }
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                Field idField = TagEntry.class.getDeclaredField("f_215913_");
                idField.setAccessible(true);
                Field tagField = TagEntry.class.getDeclaredField("f_215914_");
                tagField.setAccessible(true);
                Method locMethod = TagEntry.class.getDeclaredMethod("m_215924_");
                locMethod.setAccessible(true);
                tagEntryIdMh = lookup.unreflectGetter(idField);
                tagEntryIsTagMh = lookup.unreflectGetter(tagField);
                tagEntryLocMh = lookup.unreflect(locMethod);
            } catch (Throwable ignored) {
            }
            tagEntryHandlesResolved = true;
        }
    }

    public static void onTagWrapperTouched(TagWrapper wrapper, Object indexObj) {
        if (indexObj instanceof TagElementIndex idx && wrapper != null) {
            idx.touched.add(wrapper);
        }
    }

    public static Object removeAllTagsFrom(TagEventJS event, Object existingIndex, Object[] ignored) {
        if (event == null) {
            return existingIndex;
        }
        ensureTagEntryHandles();
        MethodHandle idMh = tagEntryIdMh;
        MethodHandle isTagMh = tagEntryIsTagMh;
        MethodHandle locMh = tagEntryLocMh;
        TagEventFilter filter = TagEventFilter.unwrap(event, ignored);
        if (filter instanceof TagEventFilter.ID idFilter && idMh != null && isTagMh != null) {
            TagElementIndex idx = existingIndex instanceof TagElementIndex t
                    ? t : buildTagElementIndex(event, idMh, isTagMh);
            ResourceLocation targetId = idFilter.id();
            ArrayList<TagWrapper> indexed = idx.byElement.remove(targetId);
            if (indexed != null) {
                for (int i = 0, n = indexed.size(); i < n; i++) {
                    stripElementFromTag(indexed.get(i), targetId, idMh, isTagMh);
                }
            }
            if (!idx.touched.isEmpty()) {
                for (TagWrapper wrapper : idx.touched) {
                    stripElementFromTag(wrapper, targetId, idMh, isTagMh);
                }
            }
            return idx;
        }
        if (locMh != null) {
            for (TagWrapper tag : event.tags.values()) {
                tag.entries.removeIf(e -> {
                    try {
                        ExtraCodecs.TagOrElementLocation loc =
                                (ExtraCodecs.TagOrElementLocation) locMh.invokeExact(e.f_216042_());
                        return filter.testTagOrElementLocation(loc);
                    } catch (Throwable t) {
                        return false;
                    }
                });
            }
        }
        return existingIndex;
    }

    private static TagElementIndex buildTagElementIndex(
            TagEventJS event, MethodHandle idMh, MethodHandle isTagMh) {
        TagElementIndex idx = new TagElementIndex();
        for (TagWrapper wrapper : event.tags.values()) {
            List<TagLoader.EntryWithSource> entries = wrapper.entries;
            for (int i = 0, n = entries.size(); i < n; i++) {
                TagEntry te = entries.get(i).f_216042_();
                try {
                    boolean isTag = (boolean) isTagMh.invokeExact(te);
                    if (!isTag) {
                        ResourceLocation id = (ResourceLocation) idMh.invokeExact(te);
                        ArrayList<TagWrapper> bucket = idx.byElement.computeIfAbsent(id, k -> new ArrayList<>(4));
                        if (bucket.isEmpty() || bucket.get(bucket.size() - 1) != wrapper) {
                            bucket.add(wrapper);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return idx;
    }

    private static void stripElementFromTag(
            TagWrapper wrapper, ResourceLocation targetId, MethodHandle idMh, MethodHandle isTagMh) {
        List<TagLoader.EntryWithSource> entries = wrapper.entries;
        if (entries.isEmpty()) {
            return;
        }
        entries.removeIf(e -> {
            TagEntry te = e.f_216042_();
            try {
                if ((boolean) isTagMh.invokeExact(te)) {
                    return false;
                }
                ResourceLocation id = (ResourceLocation) idMh.invokeExact(te);
                return targetId.equals(id);
            } catch (Throwable ignored) {
                return false;
            }
        });
    }

    // ---- 4. Farmer's Delight ToolActionIngredient cache ----

    public static Stream<Ingredient.Value> toolActionValuesStream(ToolAction toolAction) {
        Collection<Item> allItems = ForgeRegistries.ITEMS.getValues();
        int regSize = allItems.size();
        if (toolAction == null) {
            return Stream.empty();
        }
        CachedToolItems cached = TOOL_ACTION_CACHE.get(toolAction);
        if (cached == null || cached.registrySize != regSize) {
            List<Item> matched = new ArrayList<>(64);
            for (Item item : allItems) {
                if (new ItemStack(item).canPerformAction(toolAction)) {
                    matched.add(item);
                }
            }
            cached = new CachedToolItems(regSize, matched.toArray(new Item[0]));
            TOOL_ACTION_CACHE.put(toolAction, cached);
        }
        Item[] items = cached.items;
        if (items.length == 0) {
            return Stream.empty();
        }
        List<Ingredient.Value> values = new ArrayList<>(items.length);
        for (int i = 0; i < items.length; i++) {
            values.add(new Ingredient.ItemValue(new ItemStack(items[i])));
        }
        return values.stream();
    }

    // ---- 5. LootJS ResourceLocationFilter$ByPattern fast matcher ----

    public static boolean testRegexResourceLocation(Pattern pattern, ResourceLocation rl) {
        if (pattern == null || rl == null) {
            return false;
        }
        String raw = pattern.pattern();
        int flags = pattern.flags();
        if (flags == 0 && (".*".equals(raw) || "^.*$".equals(raw))) {
            return true;
        }
        String key = flags == 0 ? raw : (flags + "\0" + raw);
        FastRlMatcher matcher = FAST_RL_MATCHERS.get(key);
        if (matcher == null) {
            matcher = compileFastRlMatcher(pattern, raw, flags);
            if (FAST_RL_MATCHERS.size() < MAX_FAST_RL_MATCHERS) {
                FastRlMatcher prev = FAST_RL_MATCHERS.putIfAbsent(key, matcher);
                if (prev != null) {
                    matcher = prev;
                }
            }
        }
        return matcher.matches(rl);
    }

    private static FastRlMatcher compileFastRlMatcher(Pattern pattern, String raw, int flags) {
        if (flags == 0) {
            String s = raw;
            if (s.startsWith("^")) {
                s = s.substring(1);
            }
            if (s.endsWith("$")) {
                s = s.substring(0, s.length() - 1);
            }
            if (".*".equals(s)) {
                return FastRlMatcher.MATCH_ALL;
            }
            if (isLiteralRlChars(s)) {
                return FastRlMatcher.exact(s);
            }
            if (s.startsWith(".*") && s.endsWith(".*") && s.length() > 4) {
                String mid = s.substring(2, s.length() - 2);
                if (isLiteralRlChars(mid)) {
                    return FastRlMatcher.contains(mid);
                }
            }
            int starIdx = s.indexOf(".*");
            if (starIdx >= 0 && s.indexOf(".*", starIdx + 2) < 0) {
                String pre = s.substring(0, starIdx);
                String suf = s.substring(starIdx + 2);
                if (isLiteralRlChars(pre) && isLiteralRlChars(suf)) {
                    if (suf.isEmpty()) {
                        return FastRlMatcher.prefix(pre);
                    }
                    if (pre.isEmpty()) {
                        return FastRlMatcher.suffix(suf);
                    }
                    return FastRlMatcher.prefixSuffix(pre, suf);
                }
            }
        }
        return FastRlMatcher.regex(pattern);
    }

    private static boolean isLiteralRlChars(String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '/' || c == ':';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    // ---- 6. AdvancedLootInfo AbstractServer.predicateItem read-scoped cache ----

    public static void enterAliRead() {
        AliReadContext ctx = ALI_READ_CTX.get();
        if (ctx == null) {
            int regSize = 2048;
            try {
                if (BuiltInRegistries.f_257033_ != null) {
                    regSize = Math.max(256, BuiltInRegistries.f_257033_.m_13562_());
                }
            } catch (Throwable ignored) {
            }
            ctx = new AliReadContext(regSize);
            ALI_READ_CTX.set(ctx);
        }
        ctx.depth++;
    }

    public static void exitAliRead() {
        AliReadContext ctx = ALI_READ_CTX.get();
        if (ctx == null) {
            return;
        }
        ctx.depth--;
        if (ctx.depth <= 0) {
            ALI_READ_CTX.remove();
        }
    }

    @SuppressWarnings("unchecked")
    public static boolean aliPredicateItem(Object modifier, List<?> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }
        if (modifier == null) {
            return false;
        }
        ensureAliHandles(modifier);
        MethodHandle getOps = aliGetOperationsMh;
        MethodHandle getPred = aliOpPredicateMh;
        if (getOps == null || getPred == null) {
            return false;
        }
        AliReadContext ctx = ALI_READ_CTX.get();
        if (ctx == null) {
            Predicate<ItemStack>[] preds = extractAliPredicates(modifier, getOps, getPred);
            if (preds.length == 0) {
                return false;
            }
            for (Object raw : items) {
                if (raw instanceof Item item && testAliPredicates(preds, item.m_7968_())) {
                    return true;
                }
            }
            return false;
        }
        AliModCache cache = ctx.modCaches.get(modifier);
        if (cache == null) {
            Predicate<ItemStack>[] preds = extractAliPredicates(modifier, getOps, getPred);
            cache = new AliModCache(preds, preds.length == 0 ? null : new byte[ctx.itemRegistrySize]);
            ctx.modCaches.put(modifier, cache);
        }
        if (cache.predicates.length == 0) {
            return false;
        }
        byte[] states = cache.states;
        for (Object raw : items) {
            if (!(raw instanceof Item item)) {
                continue;
            }
            int id;
            if (item == ctx.lastItem) {
                id = ctx.lastItemId;
            } else {
                id = resolveItemId(item);
                ctx.lastItem = item;
                ctx.lastItemId = id;
            }
            if (id < 0) {
                if (testAliPredicates(cache.predicates, item.m_7968_())) {
                    return true;
                }
                continue;
            }
            if (id >= states.length) {
                states = Arrays.copyOf(states, Math.max(id + 64, states.length * 2));
                cache.states = states;
            }
            byte st = states[id];
            if (st == 2) {
                return true;
            }
            if (st == 1) {
                continue;
            }
            boolean matched = testAliPredicates(cache.predicates, ctx.getDefaultStack(id, item));
            states[id] = matched ? (byte) 2 : (byte) 1;
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private static int resolveItemId(Item item) {
        try {
            return Item.m_41393_(item);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private static Predicate<ItemStack>[] extractAliPredicates(
            Object modifier, MethodHandle getOps, MethodHandle getPred) {
        try {
            List<?> ops = (List<?>) getOps.invoke(modifier);
            if (ops == null || ops.isEmpty()) {
                return EMPTY_PREDICATES;
            }
            List<Predicate<ItemStack>> list = new ArrayList<>(ops.size());
            for (int i = 0, n = ops.size(); i < n; i++) {
                try {
                    Predicate<ItemStack> p = (Predicate<ItemStack>) getPred.invoke(ops.get(i));
                    if (p != null) {
                        list.add(p);
                    }
                } catch (Throwable ignored) {
                }
            }
            return list.isEmpty() ? EMPTY_PREDICATES : list.toArray(new Predicate[0]);
        } catch (Throwable t) {
            return EMPTY_PREDICATES;
        }
    }

    private static boolean testAliPredicates(Predicate<ItemStack>[] preds, ItemStack stack) {
        for (int j = 0, m = preds.length; j < m; j++) {
            try {
                if (preds[j].test(stack)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void ensureAliHandles(Object modifier) {
        if (aliHandlesResolved) {
            return;
        }
        synchronized (ServerBootPerf.class) {
            if (aliHandlesResolved) {
                return;
            }
            try {
                ClassLoader cl = modifier.getClass().getClassLoader();
                Class<?> modIface = Class.forName("com.yanny.ali.api.ILootModifier", false, cl);
                Class<?> opIface = Class.forName("com.yanny.ali.api.IOperation", false, cl);
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                aliGetOperationsMh = lookup.findVirtual(modIface, "getOperations", MethodType.methodType(List.class));
                aliOpPredicateMh = lookup.findVirtual(opIface, "predicate", MethodType.methodType(Predicate.class));
            } catch (Throwable ignored) {
            }
            aliHandlesResolved = true;
        }
    }

    @SuppressWarnings("unchecked")
    private static final Predicate<ItemStack>[] EMPTY_PREDICATES = new Predicate[0];

    private static final class AliReadContext {
        int depth;
        final int itemRegistrySize;
        final IdentityHashMap<Object, AliModCache> modCaches = new IdentityHashMap<>(512);
        ItemStack[] defaultStacks;
        Item lastItem;
        int lastItemId = -1;

        AliReadContext(int itemRegistrySize) {
            this.itemRegistrySize = itemRegistrySize;
            this.defaultStacks = new ItemStack[itemRegistrySize];
        }

        ItemStack getDefaultStack(int id, Item item) {
            if (id >= defaultStacks.length) {
                defaultStacks = Arrays.copyOf(defaultStacks, Math.max(id + 64, defaultStacks.length * 2));
            }
            ItemStack stack = defaultStacks[id];
            if (stack == null) {
                stack = item.m_7968_();
                defaultStacks[id] = stack;
            }
            return stack;
        }
    }

    private static final class AliModCache {
        final Predicate<ItemStack>[] predicates;
        byte[] states;

        AliModCache(Predicate<ItemStack>[] predicates, byte[] states) {
            this.predicates = predicates;
            this.states = states;
        }
    }

    private static final class TagElementIndex {
        final HashMap<ResourceLocation, ArrayList<TagWrapper>> byElement = new HashMap<>(4096);
        final Set<TagWrapper> touched = Collections.newSetFromMap(new IdentityHashMap<>(64));
    }

    private static final class FastRlMatcher {
        static final FastRlMatcher MATCH_ALL = new FastRlMatcher(0, null, null, null, null);

        private final int kind; // 0=ALL, 1=EXACT, 2=PREFIX, 3=SUFFIX, 4=CONTAINS, 5=PRE_SUF, 6=REGEX
        private final String a;
        private final String b;
        private final String exactNs;
        private final ThreadLocal<Matcher> regexMatcher;

        private FastRlMatcher(int kind, String a, String b, String exactNs, Pattern regex) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            this.exactNs = exactNs;
            this.regexMatcher = regex == null ? null : ThreadLocal.withInitial(() -> regex.matcher(""));
        }

        static FastRlMatcher exact(String lit) {
            int colon = lit.indexOf(':');
            if (colon >= 0) {
                return new FastRlMatcher(1, lit.substring(colon + 1), null, lit.substring(0, colon), null);
            }
            return new FastRlMatcher(1, lit, null, null, null);
        }

        static FastRlMatcher prefix(String pre) {
            String nsOnly = pre.endsWith(":") && pre.indexOf(':') == pre.length() - 1
                    ? pre.substring(0, pre.length() - 1) : null;
            return new FastRlMatcher(2, pre, null, nsOnly, null);
        }

        static FastRlMatcher suffix(String suf) {
            return new FastRlMatcher(3, suf, null, null, null);
        }

        static FastRlMatcher contains(String mid) {
            return new FastRlMatcher(4, mid, null, null, null);
        }

        static FastRlMatcher prefixSuffix(String pre, String suf) {
            return new FastRlMatcher(5, pre, suf, null, null);
        }

        static FastRlMatcher regex(Pattern p) {
            return new FastRlMatcher(6, null, null, null, p);
        }

        boolean matches(ResourceLocation rl) {
            return switch (kind) {
                case 0 -> true;
                case 1 -> exactNs != null
                        ? exactNs.equals(rl.m_135827_()) && a.equals(rl.m_135815_())
                        : a.equals(rl.toString());
                case 2 -> exactNs != null
                        ? exactNs.equals(rl.m_135827_())
                        : rl.toString().startsWith(a);
                case 3 -> rl.toString().endsWith(a);
                case 4 -> rl.toString().contains(a);
                case 5 -> {
                    String s = rl.toString();
                    yield s.length() >= a.length() + b.length() && s.startsWith(a) && s.endsWith(b);
                }
                default -> regexMatcher.get().reset(rl.toString()).matches();
            };
        }
    }

    private static final class CachedToolItems {
        final int registrySize;
        final Item[] items;

        CachedToolItems(int registrySize, Item[] items) {
            this.registrySize = registrySize;
            this.items = items;
        }
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
