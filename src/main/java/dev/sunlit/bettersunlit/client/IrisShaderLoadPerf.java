package dev.sunlit.bettersunlit.client;

/**
 * Coordinates background loading of the active Iris shaderpack from {@code Iris.onRenderSystemInit}
 * so file I/O and preprocessor parsing overlap with Forge mod initialization instead of stalling the
 * Render thread.
 */
public final class IrisShaderLoadPerf {
    private static final Object LOCK = new Object();
    private static Thread loaderThread;
    private static volatile boolean loading;

    private IrisShaderLoadPerf() {
    }

    public static void startAsync(Runnable loadTask) {
        synchronized (LOCK) {
            Thread prev = loaderThread;
            if (prev != null && prev.isAlive() && prev != Thread.currentThread()) {
                awaitLocked(prev);
            }
            loading = true;
            Thread t = new Thread(() -> {
                try {
                    loadTask.run();
                } finally {
                    synchronized (LOCK) {
                        if (loaderThread == Thread.currentThread()) {
                            loading = false;
                            loaderThread = null;
                        }
                    }
                }
            }, "BetterSunlit-Iris-Shaderpack");
            t.setDaemon(true);
            loaderThread = t;
            t.start();
        }
    }

    public static void awaitLoaded() {
        if (!loading) {
            return;
        }
        Thread t;
        synchronized (LOCK) {
            t = loaderThread;
        }
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        awaitLocked(t);
    }

    private static void awaitLocked(Thread t) {
        boolean interrupted = false;
        while (t.isAlive()) {
            try {
                t.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
