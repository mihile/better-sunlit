package dev.sunlit.bettersunlit.perf;

import com.Pink_Cats.createschematicchecker.FancyConfig.ConfigRegister;
import com.Pink_Cats.createschematicchecker.network.SimpleJsonParser;
import com.Pink_Cats.createschematicchecker.network.rule_io;
import com.Pink_Cats.createschematicchecker.online.VersionChecker;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Offloads CreateSchematicChecker's blocking HTTPS version check from {@code modloading-worker}
 * onto a background daemon thread, returning any locally cached rule file immediately on startup.
 */
public final class CscVersionPerf {
    private static final Path ONLINE_DIR = Path.of("config", "CSC", "online");
    private static final AtomicBoolean ASYNC_STARTED = new AtomicBoolean();
    private static final ThreadLocal<Boolean> IN_BACKGROUND = new ThreadLocal<>();

    private CscVersionPerf() {
    }

    public static boolean isBackgroundThread() {
        return Boolean.TRUE.equals(IN_BACKGROUND.get());
    }

    public static String startAsyncAndGetLocalRule() {
        String latest = findLatestLocalRule();
        if (latest != null) {
            try {
                rule_io.RuleFixer(latest);
            } catch (Throwable ignored) {
            }
        }
        if (ASYNC_STARTED.compareAndSet(false, true)) {
            Thread thread = new Thread(CscVersionPerf::runBackgroundUpdate, "BetterSunlit-CSC-Update");
            thread.setDaemon(true);
            thread.start();
        }
        return latest;
    }

    private static String findLatestLocalRule() {
        if (!Files.isDirectory(ONLINE_DIR)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(ONLINE_DIR)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("rule_") && name.endsWith(".json");
                    })
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(p -> "config/CSC/online/" + p.getFileName().toString())
                    .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void runBackgroundUpdate() {
        IN_BACKGROUND.set(Boolean.TRUE);
        try {
            String path = VersionChecker.UpdateMainThread();
            if (path == null || !ConfigRegister.enable_auto_config_update) {
                return;
            }
            ArrayList<String> log = new ArrayList<>();
            String[][][] parsed = SimpleJsonParser.parseJsonToArrays(path, log);
            Method validMethod = SimpleJsonParser.class.getDeclaredMethod("ConfigIsValid", String[][][].class, List.class);
            validMethod.setAccessible(true);
            boolean valid = Boolean.TRUE.equals(validMethod.invoke(null, parsed, log));
            if (valid && parsed != null) {
                String[][] idOnline = parsed[0] != null ? parsed[0] : new String[0][0];
                String[][] opOnline = parsed[1] != null ? parsed[1] : new String[0][0];
                if (opOnline.length > 0) {
                    ConfigRegister.Operate_modify_rule_online = opOnline;
                    if (ConfigRegister.Operate_modify_rule_local != null) {
                        String[][] mergedOp = SimpleJsonParser.merge(ConfigRegister.Operate_modify_rule_local, opOnline);
                        if (ConfigRegister.enable_manual_config && ConfigRegister.Operate_modify_rule_manual != null) {
                            mergedOp = SimpleJsonParser.merge(mergedOp, ConfigRegister.Operate_modify_rule_manual);
                        }
                        ConfigRegister.Operate_match_rule = mergedOp;
                    }
                }
                if (idOnline.length > 0) {
                    ConfigRegister.ID_modify_rule_online = idOnline;
                    if (ConfigRegister.ID_modify_rule_local != null) {
                        String[][] mergedId = SimpleJsonParser.merge(ConfigRegister.ID_modify_rule_local, idOnline);
                        if (ConfigRegister.enable_manual_config && ConfigRegister.ID_modify_rule_manual != null) {
                            mergedId = SimpleJsonParser.merge(mergedId, ConfigRegister.ID_modify_rule_manual);
                        }
                        ConfigRegister.ID_match_rule = mergedId;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            IN_BACKGROUND.remove();
        }
    }
}
