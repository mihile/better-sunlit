package dev.sunlit.bettersunlit.client;

/**
 * Skips hardware probing for the throwaway report created by {@code CrashReport.preload()}.
 * Real crash reports still probe the hardware on the calling thread.
 */
public final class CrashReportPreloadPerf {
    private static final ThreadLocal<Boolean> IN_PRELOAD = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private CrashReportPreloadPerf() {
    }

    public static void enterPreload() {
        IN_PRELOAD.set(Boolean.TRUE);
    }

    public static void exitPreload() {
        IN_PRELOAD.remove();
    }

    public static boolean isInPreload() {
        return Boolean.TRUE.equals(IN_PRELOAD.get());
    }

    public static boolean shouldSkipSyncHardwareProbe() {
        return isInPreload();
    }

    public static void resetForTest() {
        IN_PRELOAD.remove();
    }
}
