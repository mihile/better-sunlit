package dev.sunlit.bettersunlit.client;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Compatibility facade delegating to common {@link dev.sunlit.bettersunlit.BootAgentSync}.
 */
public final class BootAgentSync {
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
        dev.sunlit.bettersunlit.BootAgentSync.scheduleSync();
    }

    public static byte[] loadBundledBytes() throws IOException {
        return dev.sunlit.bettersunlit.BootAgentSync.loadBundledBytes();
    }

    public static SyncOutcome syncConfigBootAgent(Path configDir, byte[] bundledBytes, boolean allowWaiterSpawn)
            throws IOException {
        dev.sunlit.bettersunlit.BootAgentSync.SyncOutcome out =
                dev.sunlit.bettersunlit.BootAgentSync.syncConfigBootAgent(configDir, bundledBytes, allowWaiterSpawn);
        return SyncOutcome.valueOf(out.name());
    }

    public static String sha256(byte[] data) {
        return dev.sunlit.bettersunlit.BootAgentSync.sha256(data);
    }

    public static String sha256(Path file) throws IOException {
        return dev.sunlit.bettersunlit.BootAgentSync.sha256(file);
    }
}
