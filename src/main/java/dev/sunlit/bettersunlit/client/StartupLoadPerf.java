package dev.sunlit.bettersunlit.client;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.util.thread.BlockableEventLoop;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.locks.LockSupport;

/**
 * Eliminates artificial main-thread stalls and excessive swap/sync overhead while the startup
 * {@link LoadingOverlay} is active: bypasses VSync/FPS throttling, keeps render calls and OS
 * window events draining frequently, and renders visual overlay frames at 20 FPS until the
 * underlying reload completes (then renders immediately so transition never stalls).
 */
public final class StartupLoadPerf {
    private static final long VISUAL_FRAME_INTERVAL_NS = 50_000_000L;
    private static final long GLFW_POLL_INTERVAL_NS = 16_000_000L;
    private static final MethodHandle GLFW_POLL_EVENTS = resolveGlfwPollEvents();

    private static volatile boolean vsyncBypassed;
    private static long lastVisualRenderNs;
    private static long lastGlfwPollNs;
    private static int postStartupReadyTicks;
    private static boolean postStartupIrisTriggered;

    private StartupLoadPerf() {
    }

    private static MethodHandle resolveGlfwPollEvents() {
        try {
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
            return MethodHandles.publicLookup().findStatic(glfw, "glfwPollEvents", MethodType.methodType(void.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isStartupLoading(Minecraft mc) {
        return mc != null && mc.f_91073_ == null && mc.m_91265_() instanceof LoadingOverlay;
    }

    public static void onRenderTickBegin(Minecraft mc) {
        if (mc == null) {
            return;
        }
        if (isStartupLoading(mc)) {
            Window window = mc.m_91268_();
            if (!vsyncBypassed && window != null && window.m_85439_() != 0L) {
                window.m_85409_(false);
                vsyncBypassed = true;
            }
        } else {
            lastVisualRenderNs = 0L;
            lastGlfwPollNs = 0L;
            syncVsync(mc);
            if (!postStartupIrisTriggered && mc.m_91265_() == null) {
                if (postStartupReadyTicks++ >= 2) {
                    postStartupIrisTriggered = true;
                    IrisShaderLoadPerf.triggerPostStartupIfPending();
                    YaclImagePerf.triggerPostStartupIfPending();
                }
            }
        }
    }

    public static boolean shouldDeferVisualFrame(Minecraft mc) {
        if (!isStartupLoading(mc)) {
            syncVsync(mc);
            return false;
        }
        if (mc.m_91265_() instanceof StartupOverlayAccess access && access.sunlit$isReloadDone()) {
            return false;
        }
        long now = System.nanoTime();
        if (lastVisualRenderNs == 0L || now - lastVisualRenderNs >= VISUAL_FRAME_INTERVAL_NS) {
            lastVisualRenderNs = now;
            return false;
        }
        return true;
    }

    public static void whileDeferringVisualFrame(Minecraft mc) {
        RenderSystem.replayQueue();
        long now = System.nanoTime();
        if (GLFW_POLL_EVENTS != null && now - lastGlfwPollNs >= GLFW_POLL_INTERVAL_NS) {
            lastGlfwPollNs = now;
            try {
                GLFW_POLL_EVENTS.invokeExact();
            } catch (Throwable ignored) {
            }
        }
        if (mc != null && ((BlockableEventLoop<?>) (Object) mc).m_18696_() == 0) {
            LockSupport.parkNanos("sunlit-startup-load", 1_000_000L);
        }
    }

    public static void syncVsync(Minecraft mc) {
        if (!vsyncBypassed || mc == null) {
            return;
        }
        Window window = mc.m_91268_();
        if (window != null && window.m_85439_() != 0L) {
            boolean userVsync = mc.f_91066_ != null && Boolean.TRUE.equals(mc.f_91066_.m_231817_().m_231551_());
            window.m_85409_(userVsync);
            vsyncBypassed = false;
        }
    }
}
