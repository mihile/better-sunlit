package dev.sunlit.bettersunlit;

import dev.sunlit.bettersunlit.update.ApplyUpdate;
import dev.sunlit.bettersunlit.update.Build;
import dev.sunlit.bettersunlit.update.SafeHttp;
import dev.sunlit.bettersunlit.update.SignedRelease;
import dev.sunlit.bettersunlit.update.UpdateFiles;
import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated-server self-updater: periodically checks the signed server feed, notifies OP players
 * whenever they log in (with restart warning), and handles /bettersunlit update by verifying the
 * download and staging the detached ApplyUpdate helper before shutting down the server.
 * The launcher controls whether the server starts again.
 */
@Mod.EventBusSubscriber(modid = SunlitFishVisuals.ID)
public final class ServerUpdateManager {
    private static final long CHECK_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final int RESTART_COUNTDOWN_TICKS = 100; // 5 seconds

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Better Sunlit server updater");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private static volatile MinecraftServer activeServer;
    private static volatile SignedRelease availableRelease;
    private static volatile long lastCheckNanos;
    private static volatile int restartTicksRemaining = -1;
    private static volatile String pendingRestartLabel = "";

    private ServerUpdateManager() {}

    public static boolean isServerChannel() {
        return "server".equals(Build.CHANNEL);
    }

    static Path gameDir() {
        return FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
    }

    static Path updateRoot() throws IOException {
        return UpdateFiles.directory(gameDir().resolve(".better-sunlit-update"));
    }

    public static boolean isUpdateAvailable(long currentBuild, SignedRelease candidate, long blockedBuild) {
        if (candidate == null) return false;
        long build = candidate.build();
        return build > currentBuild && build != blockedBuild;
    }

    static long readBlockedBuild(Path stateDir) {
        try {
            Path file = stateDir.resolve("blocked.properties");
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) return -1L;
            Properties p = SignedRelease.properties(Files.readAllBytes(file));
            String raw = p.getProperty("build");
            return raw == null || raw.isBlank() ? -1L : Long.parseLong(raw.trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    public static Component buildAvailableMessage(String currentLabel, String latestLabel) {
        return Component.m_264642_(
                "better_sunlit.server_update.available",
                "[Better Sunlit] Server update available (%s → %s).",
                currentLabel, latestLabel
        ).m_130940_(ChatFormatting.GOLD);
    }

    public static Component buildPromptMessage() {
        MutableComponent cmd = Component.m_237113_("/bettersunlit update")
                .m_130938_(style -> style
                        .m_131140_(ChatFormatting.AQUA)
                        .m_131155_(true)
                        .m_131142_(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/bettersunlit update")));
        return Component.m_264642_(
                "better_sunlit.server_update.prompt",
                "[Better Sunlit] Run %s to install it. Players will be disconnected during the update.",
                cmd
        ).m_130940_(ChatFormatting.YELLOW);
    }

    private static void notifyOp(ServerPlayer player, SignedRelease release) {
        player.m_213846_(buildAvailableMessage(Build.LABEL, release.label()));
        player.m_213846_(buildPromptMessage());
    }

    private static void notifyAllOnlineOps(MinecraftServer server, SignedRelease release) {
        if (server == null || release == null) return;
        for (ServerPlayer player : server.m_6846_().m_11314_()) {
            if (player.m_20310_(2)) {
                notifyOp(player, release);
            }
        }
        SunlitFishVisuals.LOG.info("Better Sunlit server update available: {} -> {}. Run /bettersunlit update to install it. Players will be disconnected.",
                Build.LABEL, release.label());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!isServerChannel()) return;
        activeServer = event.getServer();
        restartTicksRemaining = -1;
        pendingRestartLabel = "";
        checkInBackground(activeServer, true);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        activeServer = null;
        restartTicksRemaining = -1;
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!isServerChannel()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.m_20310_(2)) return;

        SignedRelease known = availableRelease;
        if (known != null && known.build() > Build.NUMBER) {
            notifyOp(player, known);
        } else if (activeServer != null) {
            checkInBackground(activeServer, true);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isServerChannel()) return;
        MinecraftServer server = event.getServer();
        if (server == null) return;

        int remaining = restartTicksRemaining;
        if (remaining > 0) {
            restartTicksRemaining = remaining - 1;
            if (remaining == 60 || remaining == 20) {
                broadcast(server, Component.m_264642_(
                        "better_sunlit.server_update.restarting",
                        "[Better Sunlit] Installing server version %s. Server shuts down in %s seconds.",
                        pendingRestartLabel, Integer.toString(remaining / 20)
                ).m_130940_(ChatFormatting.RED));
            }
            if (restartTicksRemaining == 0) {
                restartTicksRemaining = -1;
                broadcast(server, Component.m_264642_(
                        "better_sunlit.server_update.stopping",
                        "[Better Sunlit] Shutting down the server to install version %s.",
                        pendingRestartLabel
                ).m_130940_(ChatFormatting.RED));
                server.m_7570_(false);
            }
            return;
        }

        if (System.nanoTime() - lastCheckNanos >= CHECK_INTERVAL_NANOS) {
            checkInBackground(server, true);
        }
    }

    private static void broadcast(MinecraftServer server, Component message) {
        server.m_6846_().m_240416_(message, false);
    }

    private static void checkInBackground(MinecraftServer server, boolean notifyOnFind) {
        if (restartTicksRemaining > 0 || !BUSY.compareAndSet(false, true)) return;
        lastCheckNanos = System.nanoTime();
        IO.execute(() -> {
            try {
                SignedRelease fetched = SignedRelease.verify(new SafeHttp().manifest(), SignedRelease.publicKey());
                long blocked = readBlockedBuild(updateRoot());
                SignedRelease previous = availableRelease;
                if (isUpdateAvailable(Build.NUMBER, fetched, blocked)) {
                    availableRelease = fetched;
                    boolean newlyFound = previous == null || fetched.build() > previous.build();
                    if (notifyOnFind && newlyFound && server != null) {
                        server.execute(() -> notifyAllOnlineOps(server, fetched));
                    }
                } else {
                    availableRelease = null;
                }
            } catch (Exception e) {
                SunlitFishVisuals.LOG.debug("Better Sunlit server update check failed; retaining current version", e);
            } finally {
                BUSY.set(false);
            }
        });
    }

    public static int executeUpdateCommand(CommandSourceStack source) {
        if (!isServerChannel()) {
            source.m_81352_(Component.m_264642_(
                    "better_sunlit.server_update.client_only_warn",
                    "[Better Sunlit] Server update command is only available on a dedicated server running the server JAR."));
            return 0;
        }
        MinecraftServer server = source.m_81377_();
        if (restartTicksRemaining > 0) {
            source.m_288197_(() -> Component.m_264642_(
                    "better_sunlit.server_update.restarting",
                    "[Better Sunlit] Installing server version %s. Server shuts down in %s seconds.",
                    pendingRestartLabel, Integer.toString(Math.max(1, (restartTicksRemaining + 19) / 20))
            ).m_130940_(ChatFormatting.RED), false);
            return 1;
        }
        if (!BUSY.compareAndSet(false, true)) {
            source.m_81352_(Component.m_264642_(
                    "better_sunlit.server_update.busy",
                    "[Better Sunlit] A server update check or download is already in progress."));
            return 0;
        }

        source.m_288197_(() -> Component.m_264642_(
                "better_sunlit.server_update.checking",
                "[Better Sunlit] Checking for server updates..."
        ).m_130940_(ChatFormatting.YELLOW), false);

        IO.execute(() -> {
            try {
                SignedRelease next = SignedRelease.verify(new SafeHttp().manifest(), SignedRelease.publicKey());
                Path state = updateRoot();
                long blocked = readBlockedBuild(state);
                if (next.build() <= Build.NUMBER) {
                    availableRelease = null;
                    server.execute(() -> source.m_288197_(() -> Component.m_264642_(
                            "better_sunlit.server_update.up_to_date",
                            "[Better Sunlit] Server is already up to date (%s).",
                            Build.LABEL
                    ).m_130940_(ChatFormatting.GREEN), false));
                    return;
                }
                if (next.build() == blocked) {
                    server.execute(() -> source.m_81352_(Component.m_264642_(
                            "better_sunlit.server_update.blocked",
                            "[Better Sunlit] Latest build (%s) is blocked after a previous rollback.",
                            next.label())));
                    return;
                }

                availableRelease = next;
                server.execute(() -> source.m_288197_(() -> Component.m_264642_(
                        "better_sunlit.server_update.downloading",
                        "[Better Sunlit] Downloading and verifying server version %s…",
                        next.label()
                ).m_130940_(ChatFormatting.GOLD), true));

                stageAndSpawnHelper(gameDir(), state, next);

                server.execute(() -> {
                    pendingRestartLabel = next.label();
                    restartTicksRemaining = RESTART_COUNTDOWN_TICKS;
                    broadcast(server, Component.m_264642_(
                            "better_sunlit.server_update.restarting",
                            "[Better Sunlit] Installing server version %s. Server shuts down in %s seconds.",
                            next.label(), Integer.toString(RESTART_COUNTDOWN_TICKS / 20)
                    ).m_130940_(ChatFormatting.RED));
                });
            } catch (Exception e) {
                SunlitFishVisuals.LOG.warn("Better Sunlit server update failed; keeping current server running", e);
                String reason = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                server.execute(() -> source.m_81352_(Component.m_264642_(
                        "better_sunlit.server_update.failed",
                        "[Better Sunlit] Could not prepare the server update (%s). The current version is still running.",
                        reason)));
            } finally {
                BUSY.set(false);
            }
        });
        return 1;
    }

    private static void stageAndSpawnHelper(Path game, Path state, SignedRelease next) throws Exception {
        Path target = UpdateFiles.target(game);
        String oldHash = UpdateFiles.sha(target);
        if (UpdateFiles.installedBuild(target) != Build.NUMBER) {
            throw new IOException("Installed server JAR changed on disk");
        }

        Path plan = state.resolve("pending.properties");
        Path worker = state.resolve("worker.properties");
        if (Files.isRegularFile(worker)) {
            Properties prev = SignedRelease.properties(Files.readAllBytes(worker));
            var h = ProcessHandle.of(Long.parseLong(prev.getProperty("pid", "-1")));
            if (h.isPresent() && h.get().isAlive()
                    && h.get().info().startInstant().map(i -> i.toString().equals(prev.getProperty("started"))).orElse(false)) {
                // Helper already waiting for this server process to exit.
                return;
            }
        }

        new SafeHttp().download(next, state.resolve("incoming"), n -> {});

        String helperHash = Build.META.getProperty("helperSha256");
        if (helperHash == null || helperHash.isBlank()) {
            throw new IOException("Missing helper checksum");
        }
        Path helper = UpdateFiles.directory(state.resolve("helpers")).resolve("apply-" + helperHash + ".jar");
        if (!Files.exists(helper)) {
            try (var in = ServerUpdateManager.class.getResourceAsStream("/assets/better_sunlit/update-helper.jar")) {
                if (in == null) throw new IOException("Missing bundled update helper");
                Files.copy(in, helper);
            }
        }
        if (Files.isSymbolicLink(helper) || !UpdateFiles.sha(helper).equals(helperHash)) {
            throw new IOException("Helper checksum mismatch");
        }

        UpdateFiles.writeBytes(state.resolve("manifest.properties"), next.envelope());

        var self = ProcessHandle.current();
        String started = self.info().startInstant().orElseThrow().toString();
        Properties p = new Properties();
        p.setProperty("game", game.toRealPath().toString());
        p.setProperty("build", Long.toString(next.build()));
        p.setProperty("oldSha256", oldHash);
        p.setProperty("parentPid", Long.toString(self.pid()));
        p.setProperty("parentStarted", started);
        UpdateFiles.writeProperties(plan, p);

        // Also record crash-recovery metadata so Update-Server.ps1 (if installed) can roll back on failed boot.
        Properties recovery = new Properties();
        recovery.setProperty("build", Long.toString(next.build()));
        recovery.setProperty("newSha256", next.sha256());
        recovery.setProperty("oldSha256", oldHash);
        UpdateFiles.writeProperties(state.resolve("server-pending.properties"), recovery);

        Path javaBin = resolveJavaExecutable();
        Path log = state.resolve("apply.log");
        if (Files.isSymbolicLink(log)) {
            throw new IOException("Linked updater log");
        }
        Process child = new ProcessBuilder(
                javaBin.toString(),
                "-Xms8m", "-Xmx64m", "-Djava.awt.headless=true",
                "-cp", helper.toString(),
                ApplyUpdate.class.getName(),
                plan.toString()
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();

        Properties w = new Properties();
        w.setProperty("pid", Long.toString(child.pid()));
        w.setProperty("started", child.toHandle().info().startInstant().orElseThrow().toString());
        UpdateFiles.writeProperties(worker, w);

        Thread.sleep(300);
        if (!child.isAlive()) {
            throw new IOException("Update helper exited prematurely; see .better-sunlit-update/apply.log");
        }
    }

    private static Path resolveJavaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        if (windows) {
            Path javaw = bin.resolve("javaw.exe");
            if (Files.isRegularFile(javaw)) return javaw;
            return bin.resolve("java.exe");
        }
        return bin.resolve("java");
    }
}
