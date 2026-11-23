package dev.sunlit.bettersunlit.client;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Pre-fetches and JSON-parses {@code ClientLanguage} entries across {@code ForkJoinPool} workers
 * (starting early during {@code LanguageManager} prepare when possible) while replaying the
 * results in exact {@code (langCode, namespace, pack)} merge order.
 */
public final class ClientLanguagePerf {
    private static final Logger LOG = LoggerFactory.getLogger("net.minecraft.client.resources.language.ClientLanguage");
    private static final int BATCH_SIZE = 24;
    private static final ThreadLocal<Deque<Session>> ACTIVE_SESSIONS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ConcurrentHashMap<ResourceManager, PendingPrep> PREPARED_BY_RM =
            new ConcurrentHashMap<>();

    private ClientLanguagePerf() {
    }

    public static HashMap<String, String> newPresizedLanguageMap() {
        Deque<Session> stack = ACTIVE_SESSIONS.get();
        if (!stack.isEmpty()) {
            Session session = stack.peek();
            if (session != null) {
                HashMap<String, String> premerged = session.claimPremergedMap();
                if (premerged != null) {
                    return premerged;
                }
            }
        }
        return new HashMap<>(131072, 0.75f);
    }

    public static CompletableFuture<Void> prepareSessionAsync(
            ResourceManager resourceManager, List<String> filenames, Executor executor) {
        if (resourceManager == null || filenames == null || filenames.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<String> langsCopy = List.copyOf(filenames);
        CompletableFuture<Session> future = CompletableFuture.supplyAsync(
                () -> buildSession(resourceManager, langsCopy, ForkJoinPool.commonPool()),
                executor != null ? executor : ForkJoinPool.commonPool());
        PREPARED_BY_RM.put(resourceManager, new PendingPrep(langsCopy, future));
        return future.thenAccept(ignored -> {
        });
    }

    public static void beginSession(ResourceManager resourceManager, List<String> filenames) {
        if (resourceManager == null || filenames == null || filenames.isEmpty()) {
            return;
        }
        PendingPrep pending = PREPARED_BY_RM.remove(resourceManager);
        if (pending != null && pending.langs.equals(filenames)) {
            Session preloaded = pending.future.join();
            if (preloaded != null) {
                ACTIVE_SESSIONS.get().push(preloaded);
                return;
            }
        }
        Session session = buildSession(resourceManager, filenames, ForkJoinPool.commonPool());
        if (session != null) {
            ACTIVE_SESSIONS.get().push(session);
        }
    }

    private static Session buildSession(
            ResourceManager resourceManager, List<String> filenames, Executor pool) {
        Set<String> namespaceSet = resourceManager.m_7187_();
        if (namespaceSet == null || namespaceSet.size() < 4) {
            return null;
        }
        List<String> namespaces = new ArrayList<>(namespaceSet);
        List<TaskSpec> tasks = new ArrayList<>(filenames.size() * namespaces.size());
        for (String langCode : filenames) {
            String path = String.format(Locale.ROOT, "lang/%s.json", langCode);
            for (String namespace : namespaces) {
                tasks.add(new TaskSpec(langCode, new ResourceLocation(namespace, path)));
            }
        }
        return preload(resourceManager, tasks, pool);
    }

    public static List<Resource> interceptGetResourceStack(
            ResourceManager resourceManager, ResourceLocation location) {
        Deque<Session> stack = ACTIVE_SESSIONS.get();
        if (stack.isEmpty()) {
            return resourceManager.m_213829_(location);
        }
        Session session = stack.peek();
        if (session == null || !session.handles(location)) {
            return resourceManager.m_213829_(location);
        }
        session.lastQueriedLocation = location;
        return Collections.emptyList();
    }

    public static boolean tryApplyPreloaded(
            String langCode,
            List<Resource> resources,
            Map<String, String> target) {
        Deque<Session> stack = ACTIVE_SESSIONS.get();
        if (stack.isEmpty() || !resources.isEmpty()) {
            return false;
        }
        Session session = stack.peek();
        if (session == null) {
            return false;
        }
        ResourceLocation loc = session.lastQueriedLocation;
        session.lastQueriedLocation = null;
        if (loc == null) {
            return false;
        }
        EntryResult result = session.consume(langCode, loc);
        if (result == null) {
            return false;
        }
        if (result.error != null) {
            if (result.error instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(result.error);
        }
        if (target == session.claimedPremergedMap) {
            return true;
        }
        if (!result.entries.isEmpty()) {
            target.putAll(result.entries);
        }
        return true;
    }

    public static void endSession() {
        Deque<Session> stack = ACTIVE_SESSIONS.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            ACTIVE_SESSIONS.remove();
        }
    }

    static Session preload(ResourceManager resourceManager, List<TaskSpec> tasks) {
        return preload(resourceManager, tasks, ForkJoinPool.commonPool());
    }

    static Session preload(ResourceManager resourceManager, List<TaskSpec> tasks, Executor pool) {
        int total = tasks.size();
        EntryResult[] results = new EntryResult[total];
        int batchCount = (total + BATCH_SIZE - 1) / BATCH_SIZE;
        List<CompletableFuture<Void>> futures = new ArrayList<>(batchCount);
        for (int start = 0; start < total; start += BATCH_SIZE) {
            final int from = start;
            final int to = Math.min(total, from + BATCH_SIZE);
            futures.add(CompletableFuture.runAsync(() -> {
                for (int i = from; i < to; i++) {
                    results[i] = loadOne(resourceManager, tasks.get(i));
                }
            }, pool));
        }
        for (CompletableFuture<Void> future : futures) {
            future.join();
        }

        Map<String, Map<ResourceLocation, EntryResult>> byLang = new HashMap<>(tasks.size());
        for (int i = 0; i < total; i++) {
            TaskSpec spec = tasks.get(i);
            EntryResult res = results[i];
            if (res == null) {
                continue;
            }
            byLang.computeIfAbsent(spec.langCode, k -> new Object2ObjectOpenHashMap<>())
                    .put(spec.location, res);
        }
        HashMap<String, String> premerged = null;
        if (total >= 4) {
            premerged = new HashMap<>(131072, 0.75f);
            for (int i = 0; i < total; i++) {
                EntryResult res = results[i];
                if (res != null && res.error == null && !res.entries.isEmpty()) {
                    premerged.putAll(res.entries);
                }
            }
        }
        return new Session(byLang, premerged);
    }

    private static EntryResult loadOne(ResourceManager resourceManager, TaskSpec spec) {
        try {
            List<Resource> resources = resourceManager.m_213829_(spec.location);
            if (resources.isEmpty()) {
                return EntryResult.EMPTY;
            }
            Map<String, String> entries = new Object2ObjectOpenHashMap<>();
            for (Resource resource : resources) {
                if (KubeJsPackPerf.tryLoadDirectLang(resource, spec.location, entries)) {
                    continue;
                }
                try (InputStream stream = resource.m_215507_()) {
                    Language.m_128108_(stream, entries::put);
                } catch (IOException ex) {
                    LOG.warn("Failed to load translations for {} from pack {}",
                            spec.langCode, resource.m_215506_(), ex);
                }
            }
            return entries.isEmpty() ? EntryResult.EMPTY : new EntryResult(entries, null);
        } catch (Exception ex) {
            return new EntryResult(Collections.emptyMap(), ex);
        }
    }

    private record PendingPrep(List<String> langs, CompletableFuture<Session> future) {
    }

    static final class TaskSpec {
        final String langCode;
        final ResourceLocation location;

        TaskSpec(String langCode, ResourceLocation location) {
            this.langCode = langCode;
            this.location = location;
        }
    }

    private static final class EntryResult {
        static final EntryResult EMPTY = new EntryResult(Collections.emptyMap(), null);
        final Map<String, String> entries;
        final Exception error;

        EntryResult(Map<String, String> entries, Exception error) {
            this.entries = entries;
            this.error = error;
        }
    }

    static final class Session {
        private final Map<String, Map<ResourceLocation, EntryResult>> byLang;
        private HashMap<String, String> premergedMap;
        private HashMap<String, String> claimedPremergedMap;
        private ResourceLocation lastQueriedLocation;

        Session(Map<String, Map<ResourceLocation, EntryResult>> byLang, HashMap<String, String> premergedMap) {
            this.byLang = byLang;
            this.premergedMap = premergedMap;
        }

        HashMap<String, String> claimPremergedMap() {
            HashMap<String, String> map = this.premergedMap;
            if (map != null) {
                this.premergedMap = null;
                this.claimedPremergedMap = map;
            }
            return map;
        }

        boolean handles(ResourceLocation location) {
            if (location == null) {
                return false;
            }
            for (Map<ResourceLocation, EntryResult> map : byLang.values()) {
                if (map.containsKey(location)) {
                    return true;
                }
            }
            return false;
        }

        EntryResult consume(String langCode, ResourceLocation location) {
            Map<ResourceLocation, EntryResult> map = byLang.get(langCode);
            return map == null ? null : map.remove(location);
        }
    }
}
