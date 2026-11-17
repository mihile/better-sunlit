package dev.sunlit.bettersunlit.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Resolves EMF's String (3.2.4) or ThreadLocal (3.3.12) context once. */
public final class EmfLoadingContext {
    /** Supplied only by the reviewed concurrent-cache mixins, after actual application. */
    public interface ConcurrentModelManager {}
    public interface ConcurrentTextureManager {}

    private final MethodHandle specified;
    private final MethodHandle blockEntity;
    private final boolean threadLocal;

    private EmfLoadingContext(MethodHandle specified, MethodHandle blockEntity, boolean threadLocal) {
        this.specified = specified;
        this.blockEntity = blockEntity;
        this.threadLocal = threadLocal;
    }

    public static EmfLoadingContext resolve(Class<?> manager) {
        try {
            var field = manager.getField("currentSpecifiedModelLoading");
            if (field.getType() != String.class && field.getType() != ThreadLocal.class) {
                return new EmfLoadingContext(null, null, false);
            }
            var lookup = MethodHandles.publicLookup();
            var getter = MethodType.methodType(Object.class, Object.class);
            return new EmfLoadingContext(lookup.unreflectGetter(field).asType(getter),
                    lookup.unreflectGetter(manager.getField("currentBlockEntityTypeLoading")).asType(getter),
                    field.getType() == ThreadLocal.class);
        } catch (ReflectiveOperationException | SecurityException e) {
            // Unknown shapes keep the normal synchronous path; never guess a field descriptor.
            return new EmfLoadingContext(null, null, false);
        }
    }

    public boolean hasSpecialContext(Object manager) {
        if (manager == null) return false;
        if (specified == null) return true;
        try {
            if ((Object) blockEntity.invokeExact(manager) != null) return true;
            Object value = (Object) specified.invokeExact(manager);
            if (threadLocal) {
                if (!(value instanceof ThreadLocal<?> local)) return true;
                value = local.get();
            }
            return value != null && (!(value instanceof String name) || !name.isEmpty());
        } catch (Throwable e) {
            return true;
        }
    }

    public boolean canBakeEmfInParallel(Object manager, Object textures) {
        return specified != null && threadLocal
                && manager instanceof ConcurrentModelManager
                && textures instanceof ConcurrentTextureManager;
    }
}
