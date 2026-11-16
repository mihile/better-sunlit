package dev.sunlit.bettersunlit.perf;

import org.apache.logging.log4j.LogManager;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Lock-free cached replacement for Radium's {@code ReflectionUtil.hasMethodOverride} that
 * eliminates {@code NoSuchMethodException} stacktrace allocations on every superclass miss.
 */
public final class ReflectionPerf {
    private static final Method[] ERROR_SENTINEL = new Method[0];
    private static volatile Throwable lastDeclaredError;

    private static final ClassValue<Method[]> DECLARED_METHODS = new ClassValue<>() {
        @Override
        protected Method[] computeValue(Class<?> type) {
            try {
                return type.getDeclaredMethods();
            } catch (NoClassDefFoundError | RuntimeException e) {
                lastDeclaredError = e;
                return ERROR_SENTINEL;
            }
        }
    };

    private ReflectionPerf() {
    }

    public static boolean hasMethodOverride(
            Class<?> clazz,
            Class<?> superClass,
            boolean fallbackResult,
            String methodName,
            Class<?>... methodArgs) {
        if (methodName == null) {
            return false;
        }
        int argCount = methodArgs == null ? 0 : methodArgs.length;
        Class<?> cur = clazz;
        while (cur != null && cur != superClass && superClass.isAssignableFrom(cur)) {
            Method[] methods = DECLARED_METHODS.get(cur);
            if (methods == ERROR_SENTINEL) {
                Throwable err = lastDeclaredError;
                LogManager.getLogger("Radium Class Analysis").warn(
                        "Failed to scan class '{}' for method '{}': {} ({})",
                        cur.getName(),
                        methodName,
                        err != null ? err.getClass().getSimpleName() : "LinkageError",
                        err != null ? err.getMessage() : "cached error");
                return fallbackResult;
            }
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (!m.getName().equals(methodName) || m.getParameterCount() != argCount) {
                    continue;
                }
                if (argCount == 0 || Arrays.equals(m.getParameterTypes(), methodArgs)) {
                    return true;
                }
            }
            cur = cur.getSuperclass();
        }
        return false;
    }
}
