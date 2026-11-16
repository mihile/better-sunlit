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
import java.util.concurrent.ForkJoinPool;

/**
 * Pre-fetches and JSON-parses {@code ClientLanguage} entries across {@code ForkJoinPool} workers
 * while replaying the results in exact {@code (langCode, namespace, pack)} merge order.
 */
public final class ClientLanguagePerf {
    private static final Logger LOG = LoggerFactory.getLogger("net.minecraft.client.resources.language.ClientLanguage");
    private static final int BATCH_SIZE = 24;
    private static final ThreadLocal<Deque<Session>> ACTIVE_SESSIONS =
            ThreadLocal.withInitial(ArrayDeque::new);

    private ClientLanguagePerf() {
    }

    public static void beginSession(ResourceManager resourceManager, List<String> filenames) {
        if (resourceManager == null || filenames == null || filenames.isEmpty()) {
            return;
        }
        Set<String> namespaceSet = resourceManager.m_7187_();
        if (namespaceSet == null || namespaceSet.size() < 4) {
            return;
        }
        List<String> namespaces = new ArrayList<>(namespaceSet);
        List<TaskSpec> tasks = new ArrayList<>(filenames.size() * namespaces.size());
        for (String langCode : filenames) {
            String path = String.format(Locale.ROOT, "lang/%s.json", langCode);
            for (String namespace : namespaces) {
                tasks.add(new TaskSpec(langCode, new ResourceLocation(namespace, path)));
            }
        }
        Session session = preload(resourceManager, tasks);
        ACTIVE_SESSIONS.get().push(session);
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
        int total = tasks.size();
        EntryResult[] results = new EntryResult[total];
        ForkJoinPool pool = ForkJoinPool.commonPool();
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
        return new Session(byLang);
    }

    private static EntryResult loadOne(ResourceManager resourceManager, TaskSpec spec) {
        try {
            List<Resource> resources = resourceManager.m_213829_(spec.location);
            if (resources.isEmpty()) {
                return EntryResult.EMPTY;
            }
            Map<String, String> entries = new Object2ObjectOpenHashMap<>();
            for (Resource resource : resources) {
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
        private ResourceLocation lastQueriedLocation;

        Session(Map<String, Map<ResourceLocation, EntryResult>> byLang) {
            this.byLang = byLang;
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
