package dev.sunlit.bettersunlit.perf;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Resolves Citadel's static contributor/patreon text directly from the bundled mod resources when
 * available so startup never blocks on synchronous HTTP timeouts.
 */
public final class CitadelWebPerf {
    private CitadelWebPerf() {
    }

    public static BufferedReader openBundledReader(String backupFileLocation, Class<?> owner) {
        if (backupFileLocation == null || backupFileLocation.isEmpty() || owner == null) {
            return null;
        }
        ClassLoader loader = owner.getClassLoader();
        if (loader == null) {
            return null;
        }
        InputStream stream = loader.getResourceAsStream(backupFileLocation);
        if (stream == null && backupFileLocation.startsWith("/")) {
            stream = loader.getResourceAsStream(backupFileLocation.substring(1));
        }
        if (stream == null) {
            return null;
        }
        return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
