package dev.sunlit.bettersunlit;

import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Extracts and keeps {@code config/sunlit-boot.jar} in sync with the bundled boot agent payload
 * inside both client and dedicated-server mod JARs. On Windows, if the running JVM holds a lock
 * via {@code -javaagent:config/sunlit-boot.jar}, the replacement is staged and applied cleanly
 * immediately after process exit.
 */
public final class BootAgentSync {
    private static final String RESOURCE_PATH = "/assets/better_sunlit/sunlit-boot.jar";
    private static final String TARGET_NAME = "sunlit-boot.jar";
    private static final String STAGED_NAME = ".sunlit-boot.jar.staged";
    private static final String AGENT_MAIN = "dev.sunlit.bootagent.SunlitBootAgent";
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);

    public enum SyncOutcome {
        NO_BUNDLED,
        SKIPPED_SYMLINK,
        UP_TO_DATE,
        UPDATED_IMMEDIATE,
        STAGED_WAIT_EXIT,
        STAGED_LOCKED
    }

    private BootAgentSync() {
    }

    public static void scheduleSync() {
        if (!SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        ForkJoinPool.commonPool().execute(() -> {
            try {
                byte[] bundled = loadBundledBytes();
                if (bundled == null) {
                    return;
                }
                Path configDir = FMLPaths.CONFIGDIR.get();
                SyncOutcome outcome = syncConfigBootAgent(configDir, bundled, true);
                if (outcome == SyncOutcome.UPDATED_IMMEDIATE) {
                    SunlitFishVisuals.LOG.info("[BetterSunlit] Synced config/{} ({} bytes)", TARGET_NAME, bundled.length);
                } else if (outcome == SyncOutcome.STAGED_WAIT_EXIT) {
                    SunlitFishVisuals.LOG.info("[BetterSunlit] Scheduled config/{} update on process exit", TARGET_NAME);
                }
            } catch (Throwable t) {
                SunlitFishVisuals.LOG.warn("[BetterSunlit] Failed syncing config/{}: {}", TARGET_NAME, t.getMessage());
            }
        });
    }

    public static byte[] loadBundledBytes() throws IOException {
        try (InputStream in = BootAgentSync.class.getResourceAsStream(RESOURCE_PATH)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    public static SyncOutcome syncConfigBootAgent(Path configDir, byte[] bundledBytes, boolean allowWaiterSpawn)
            throws IOException {
        if (bundledBytes == null || bundledBytes.length == 0) {
            return SyncOutcome.NO_BUNDLED;
        }
        Path normConfig = configDir.toAbsolutePath().normalize();
        Path target = normConfig.resolve(TARGET_NAME);
        Path staged = normConfig.resolve(STAGED_NAME);
        if (Files.isSymbolicLink(normConfig) || Files.isSymbolicLink(target) || Files.isSymbolicLink(staged)) {
            return SyncOutcome.SKIPPED_SYMLINK;
        }
        Files.createDirectories(normConfig);

        String expectedSha = sha256(bundledBytes);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && sha256(target).equals(expectedSha)) {
            Files.deleteIfExists(staged);
            return SyncOutcome.UP_TO_DATE;
        }

        writeAtomic(staged, bundledBytes);
        try {
            moveReplace(staged, target);
            return SyncOutcome.UPDATED_IMMEDIATE;
        } catch (IOException locked) {
            if (!allowWaiterSpawn) {
                return SyncOutcome.STAGED_LOCKED;
            }
            spawnExitReplacer(staged, target, expectedSha);
            return SyncOutcome.STAGED_WAIT_EXIT;
        }
    }

    private static void spawnExitReplacer(Path staged, Path target, String expectedSha) throws IOException {
        String javaExe = resolveJavaExecutable();
        ProcessBuilder pb = new ProcessBuilder(
                javaExe,
                "-cp", staged.toAbsolutePath().toString(),
                AGENT_MAIN,
                "--sync",
                Long.toString(ProcessHandle.current().pid()),
                staged.toAbsolutePath().toString(),
                target.toAbsolutePath().toString(),
                expectedSha);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        p.getOutputStream().close();
    }

    private static String resolveJavaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path binDir = Path.of(System.getProperty("java.home"), "bin");
        if (windows) {
            Path javaw = binDir.resolve("javaw.exe");
            if (Files.isRegularFile(javaw)) {
                return javaw.toString();
            }
            Path javaExe = binDir.resolve("java.exe");
            if (Files.isRegularFile(javaExe)) {
                return javaExe.toString();
            }
        }
        return binDir.resolve("java").toString();
    }

    private static void writeAtomic(Path dest, byte[] data) throws IOException {
        Path tmp = dest.resolveSibling("." + dest.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(tmp, data);
            moveReplace(tmp, dest);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) != -1; ) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
