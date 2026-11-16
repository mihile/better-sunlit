package dev.sunlit.bettersunlit.perf;

import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads Commonality's untrusted-site list asynchronously instead of stalling mod construction
 * on an external HTTPS request.
 */
public final class TrustedSourcePerf {
    private static final String SITES_URL = "https://api.stopmodreposts.org/minecraft/sites.txt";
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile List<URL> blacklist = List.of();

    private TrustedSourcePerf() {
    }

    public static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        if (!FMLEnvironment.production) {
            return;
        }
        Thread thread = new Thread(TrustedSourcePerf::download, "BetterSunlit-TrustedSources");
        thread.setDaemon(true);
        thread.start();
    }

    public static boolean isBlacklisted(URL url) {
        if (url == null) {
            return false;
        }
        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            return false;
        }
        for (URL blocked : blacklist) {
            if (StringUtils.containsIgnoreCase(host, blocked.getHost())) {
                return true;
            }
        }
        return false;
    }

    private static void download() {
        try {
            List<URL> loaded = new ArrayList<>();
            URL source = new URL(SITES_URL);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(source.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    try {
                        loaded.add(new URL("http://" + line));
                    } catch (MalformedURLException ignored) {
                    }
                }
            }
            blacklist = List.copyOf(loaded);
        } catch (Throwable ignored) {
        }
    }
}
