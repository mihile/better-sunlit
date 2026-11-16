package dev.sunlit.bettersunlit.client;

import journeymap.client.io.FileHandler;
import journeymap.client.io.ThemeLoader;
import journeymap.client.ui.theme.ThemePresets;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Skips redundant JourneyMap theme preset zip extraction on startup when preset directories are
 * already populated for the current JourneyMap jar version/size.
 */
public final class JourneymapThemePerf {
    private static final String STAMP_NAME = ".sunlit_theme_stamp";
    private static final AtomicBoolean INITIAL_INIT_STARTED = new AtomicBoolean(false);
    private static final ThreadLocal<Boolean> FORCE_RELOAD = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private JourneymapThemePerf() {
    }

    public static void beginInitialize(boolean forceReload) {
        boolean isInitial = INITIAL_INIT_STARTED.compareAndSet(false, true);
        FORCE_RELOAD.set(!isInitial && forceReload);
    }

    static void resetForTest() {
        INITIAL_INIT_STARTED.set(false);
        FORCE_RELOAD.remove();
    }

    public static void endInitialize() {
        boolean force = Boolean.TRUE.equals(FORCE_RELOAD.get());
        FORCE_RELOAD.remove();
        if (force) {
            return;
        }
        try {
            File rootDir = ThemeLoader.getThemeIconDir();
            if (rootDir != null && rootDir.isDirectory()) {
                Path stampPath = rootDir.toPath().resolve(STAMP_NAME);
                String expected = computeStamp();
                if (!Files.exists(stampPath) || !expected.equals(Files.readString(stampPath, StandardCharsets.UTF_8))) {
                    Files.writeString(stampPath, expected, StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean copyPresetResources(
            File targetDir,
            ResourceLocation location,
            String subDir,
            boolean overwrite) {
        if (!Boolean.TRUE.equals(FORCE_RELOAD.get()) && overwrite && isExtractedForCurrentJar(targetDir)) {
            return true;
        }
        return FileHandler.copyResources(targetDir, location, subDir, overwrite);
    }

    private static boolean isExtractedForCurrentJar(File targetDir) {
        if (targetDir == null || !targetDir.isDirectory()) {
            return false;
        }
        String[] entries = targetDir.list();
        if (entries == null || entries.length == 0) {
            return false;
        }
        Path stampPath = targetDir.toPath().resolve(STAMP_NAME);
        if (!Files.isRegularFile(stampPath)) {
            return false;
        }
        try {
            String actual = Files.readString(stampPath, StandardCharsets.UTF_8);
            return computeStamp().equals(actual);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String computeStamp() {
        long size = 0L;
        long mtime = 0L;
        String srcName = "";
        try {
            URL loc = ThemeLoader.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null) {
                File f = new File(loc.toURI());
                if (f.exists()) {
                    size = f.length();
                    mtime = f.lastModified();
                    srcName = f.getName();
                } else {
                    srcName = loc.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        return srcName + "|" + size + "|" + mtime + "|" + ThemePresets.getPresetDirs().size();
    }
}
