package dev.sunlit.bettersunlit.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long JOIN_TICK_CAP_WINDOW_NS = 4_500_000_000L;
    private static final long JOIN_CHUNK_PACING_WINDOW_NS = 5_000_000_000L;
    private static final int JOIN_CHUNK_UPLOAD_CAP = 6;
    private static final int STEADY_CHUNK_UPLOAD_CAP = 16;
    private static final int JOIN_MAX_QUEUED_JOBS = 6;
    private static final int BACKLOG_JOIN_MAX_QUEUED_JOBS = 2;
    private static final int BACKLOG_STEADY_MAX_QUEUED_JOBS = 6;
    private static volatile boolean joinPacingEnabled = true;
    private static java.lang.ref.WeakReference<Object> lastJoinedLevel;
    private static volatile long worldJoinStartNs;
    private static volatile boolean chunkUploadBacklogged;

    private StartupLoadPerf() {
    }

    public static boolean isJoinPacingEnabled() {
        return joinPacingEnabled;
    }

    public static void setJoinPacingEnabled(boolean enabled) {
        joinPacingEnabled = enabled;
        if (!enabled) {
            chunkUploadBacklogged = false;
        }
    }

    private static Path settingsFile() {
        return FMLPaths.CONFIGDIR.get().resolve("sunlit-client-perf.json");
    }

    public static boolean readJoinPacingEnabled(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        JsonObject json = GSON.fromJson(text, JsonObject.class);
        if (json == null || !json.has("joinLoadingPacing") || !json.get("joinLoadingPacing").isJsonPrimitive()) {
            return true;
        }
        var prim = json.get("joinLoadingPacing").getAsJsonPrimitive();
        return !prim.isBoolean() || prim.getAsBoolean();
    }

    public static void loadSettings() {
        try {
            Path file = settingsFile();
            if (!Files.exists(file)) {
                saveSettings(true);
                setJoinPacingEnabled(true);
                return;
            }
            setJoinPacingEnabled(readJoinPacingEnabled(Files.readString(file)));
        } catch (Exception ex) {
            SunlitFishVisuals.LOG.warn("Cannot load client optimize settings; using defaults", ex);
            setJoinPacingEnabled(true);
        }
    }

    public static void saveSettings(boolean enabled) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("joinLoadingPacing", enabled);
        Path file = settingsFile();
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(json) + "\n");
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * During the first 4.5s after entering a world, caps catch-up client ticks per frame from 10
     * to 2 so an initial join/chunk-upload frame spike never snowballs into 5-10 back-to-back ticks
     * in a single frame. Packet processing ({@code runAllTasks}) still runs every frame uncapped.
     */
    public static int clampJoinCatchupTicks(Minecraft mc, int vanillaMax, int elapsedTicks) {
        if (mc == null || mc.f_91073_ == null) {
            lastJoinedLevel = null;
            worldJoinStartNs = 0L;
            chunkUploadBacklogged = false;
            return Math.min(vanillaMax, elapsedTicks);
        }
        Object curLevel = mc.f_91073_;
        Object prevLevel = lastJoinedLevel != null ? lastJoinedLevel.get() : null;
        long now = System.nanoTime();
        if (curLevel != prevLevel) {
            lastJoinedLevel = new java.lang.ref.WeakReference<>(curLevel);
            worldJoinStartNs = now;
            chunkUploadBacklogged = false;
        }
        if (!joinPacingEnabled) {
            return Math.min(vanillaMax, elapsedTicks);
        }
        if (worldJoinStartNs != 0L && (now - worldJoinStartNs) < JOIN_TICK_CAP_WINDOW_NS) {
            return Math.min(Math.min(2, vanillaMax), elapsedTicks);
        }
        return Math.min(vanillaMax, elapsedTicks);
    }

    public static boolean isInChunkJoinWindow() {
        long start = worldJoinStartNs;
        return start != 0L && (System.nanoTime() - start) < JOIN_CHUNK_PACING_WINDOW_NS;
    }

    /**
     * Per-frame cap for Embeddium {@code RenderSectionManager.uploadChunks()} so 50-100+ finished
     * chunk sections never flush into {@code GlBufferArena} in a single frame and stall the GPU.
     */
    public static int getChunkUploadBudget() {
        if (!joinPacingEnabled) {
            return Integer.MAX_VALUE;
        }
        return isInChunkJoinWindow() ? JOIN_CHUNK_UPLOAD_CAP : STEADY_CHUNK_UPLOAD_CAP;
    }

    public static void setChunkUploadBacklogged(boolean backlogged) {
        chunkUploadBacklogged = joinPacingEnabled && backlogged;
    }

    /**
     * Smooths Embeddium {@code ChunkBuilder.getSchedulingBudget()} during the first 5s post-join
     * (and when {@code buildResults} already has finished meshes waiting to upload) so 10 worker
     * threads do not saturate CPU/L3 alongside Render thread + C2 JIT on world entry.
     */
    public static int clampChunkSchedulingBudget(int rawBudget, int queueSize) {
        if (rawBudget <= 0) {
            return 0;
        }
        if (!joinPacingEnabled) {
            return rawBudget;
        }
        if (isInChunkJoinWindow()) {
            int maxQueued = chunkUploadBacklogged ? BACKLOG_JOIN_MAX_QUEUED_JOBS : JOIN_MAX_QUEUED_JOBS;
            int allowed = maxQueued - queueSize;
            return allowed <= 0 ? 0 : Math.min(rawBudget, allowed);
        }
        if (chunkUploadBacklogged) {
            int allowed = BACKLOG_STEADY_MAX_QUEUED_JOBS - queueSize;
            return allowed <= 0 ? 0 : Math.min(rawBudget, allowed);
        }
        return rawBudget;
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
