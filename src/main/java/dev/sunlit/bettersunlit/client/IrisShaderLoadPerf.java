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
    private static volatile Runnable deferredTask;

    private IrisShaderLoadPerf() {
    }

    private static void setDeferredPendingFlag(boolean pending) {
        try {
            Class.forName("dev.sunlit.bootagent.payload.BootJarPayload")
                    .getField("irisDeferredPending")
                    .setBoolean(null, pending);
        } catch (Throwable ignored) {
        }
    }

    public static void deferUntilPostStartup(Runnable loadTask) {
        if (loadTask == null) {
            return;
        }
        setDeferredPendingFlag(true);
        synchronized (LOCK) {
            deferredTask = loadTask;
        }
    }

    public static void triggerPostStartupIfPending() {
        Runnable task = deferredTask;
        if (task == null) {
            return;
        }
        synchronized (LOCK) {
            task = deferredTask;
            if (task == null) {
                return;
            }
            deferredTask = null;
        }
        startAsync(task);
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
                    setDeferredPendingFlag(false);
                    synchronized (LOCK) {
                        if (loaderThread == Thread.currentThread()) {
                            loading = false;
                            loaderThread = null;
                        }
                    }
                    try {
                        var mc = net.minecraft.client.Minecraft.m_91087_();
                        if (mc != null) {
                            mc.execute(() -> {
                                try {
                                    Class.forName("dev.engine_room.flywheel.impl.BackendManagerImpl")
                                            .getMethod("onEndClientResourceReload", boolean.class)
                                            .invoke(null, false);
                                } catch (Throwable ignored) {
                                }
                            });
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, "BetterSunlit-Iris-Shaderpack");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            loaderThread = t;
            t.start();
        }
    }

    public static void awaitLoaded() {
        if (deferredTask != null) {
            triggerPostStartupIfPending();
        }
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
