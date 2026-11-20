package dev.sunlit.bettersunlit.perf;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Indexes Forge {@code ObjectHolderRegistry} handlers by target registry {@link ResourceLocation} so
 * per-registry calls inside {@code GameData.postRegisterEvents()} evaluate {@code filter.test(regName)}
 * once per registry (~50 checks) rather than scanning ~25,000+ handlers on every registry event.
 */
public final class ObjectHolderPerf {
    public interface TargetedHolder {
        ResourceLocation sunlit$registryName();
    }

    private static final Object LOCK = new Object();
    private static final Map<ResourceLocation, Bucket> BY_REGISTRY = new HashMap<>(64);
    private static final List<Consumer<Predicate<ResourceLocation>>> UNTARGETED = new ArrayList<>();
    private static final Map<Consumer<Predicate<ResourceLocation>>, Bucket> REGISTERED = new IdentityHashMap<>();

    private ObjectHolderPerf() {
    }

    public static void addHandler(Consumer<Predicate<ResourceLocation>> handler) {
        if (handler == null) {
            return;
        }
        if (handler instanceof TargetedHolder targeted) {
            ResourceLocation reg = targeted.sunlit$registryName();
            if (reg != null) {
                addTargetedHandler(reg, handler, false);
                return;
            }
        }
        synchronized (LOCK) {
            if (!REGISTERED.containsKey(handler)) {
                UNTARGETED.add(handler);
                REGISTERED.put(handler, Bucket.UNTARGETED_SENTINEL);
            }
        }
    }

    public static void addTargetedHandler(
            ResourceLocation registryName,
            Consumer<Predicate<ResourceLocation>> handler,
            boolean needsFirstPassCheck) {
        if (handler == null) {
            return;
        }
        if (registryName == null) {
            addHandler(handler);
            return;
        }
        synchronized (LOCK) {
            if (REGISTERED.containsKey(handler)) {
                return;
            }
            Bucket bucket = BY_REGISTRY.get(registryName);
            if (bucket == null) {
                bucket = new Bucket(registryName);
                BY_REGISTRY.put(registryName, bucket);
            }
            bucket.handlers.add(handler);
            if (needsFirstPassCheck) {
                bucket.needsFirstPassCheck = true;
            }
            REGISTERED.put(handler, bucket);
        }
    }

    public static boolean removeHandler(Consumer<Predicate<ResourceLocation>> handler) {
        if (handler == null) {
            return false;
        }
        synchronized (LOCK) {
            Bucket bucket = REGISTERED.remove(handler);
            if (bucket == null) {
                return false;
            }
            if (bucket == Bucket.UNTARGETED_SENTINEL) {
                return UNTARGETED.remove(handler);
            }
            return bucket.handlers.remove(handler);
        }
    }

    public static void applyObjectHolders(Predicate<ResourceLocation> filter) {
        if (filter == null) {
            return;
        }
        List<Consumer<Predicate<ResourceLocation>>[]> batches = new ArrayList<>(4);
        List<Bucket> validatedBuckets = null;
        Consumer<Predicate<ResourceLocation>>[] untargetedSnapshot = null;
        synchronized (LOCK) {
            for (Bucket bucket : BY_REGISTRY.values()) {
                if (bucket.handlers.isEmpty()) {
                    continue;
                }
                boolean firstPass = bucket.needsFirstPassCheck;
                if (firstPass || filter.test(bucket.registryName)) {
                    @SuppressWarnings("unchecked")
                    Consumer<Predicate<ResourceLocation>>[] arr =
                            bucket.handlers.toArray(new Consumer[0]);
                    batches.add(arr);
                    if (firstPass) {
                        if (validatedBuckets == null) {
                            validatedBuckets = new ArrayList<>(8);
                        }
                        validatedBuckets.add(bucket);
                        bucket.needsFirstPassCheck = false;
                    }
                }
            }
            if (!UNTARGETED.isEmpty()) {
                @SuppressWarnings("unchecked")
                Consumer<Predicate<ResourceLocation>>[] arr =
                        UNTARGETED.toArray(new Consumer[0]);
                untargetedSnapshot = arr;
            }
        }

        RuntimeException failures = new RuntimeException(
                "Failed to apply some object holders, see suppressed exceptions for details");
        for (int b = 0, bn = batches.size(); b < bn; b++) {
            Consumer<Predicate<ResourceLocation>>[] arr = batches.get(b);
            for (Consumer<Predicate<ResourceLocation>> consumer : arr) {
                try {
                    consumer.accept(filter);
                } catch (Exception ex) {
                    failures.addSuppressed(ex);
                }
            }
        }
        if (untargetedSnapshot != null) {
            for (Consumer<Predicate<ResourceLocation>> consumer : untargetedSnapshot) {
                try {
                    consumer.accept(filter);
                } catch (Exception ex) {
                    failures.addSuppressed(ex);
                }
            }
        }
        if (failures.getSuppressed().length > 0) {
            throw failures;
        }
    }

    private static final class Bucket {
        static final Bucket UNTARGETED_SENTINEL = new Bucket(null);
        final ResourceLocation registryName;
        final List<Consumer<Predicate<ResourceLocation>>> handlers = new ArrayList<>();
        boolean needsFirstPassCheck;

        Bucket(ResourceLocation registryName) {
            this.registryName = registryName;
        }
    }
}
