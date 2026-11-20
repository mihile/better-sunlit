package dev.sunlit.bootagent;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/**
 * Thin {@code Premain-Class} and {@code --sync} CLI entry point. Keeps zero ASM references so
 * bootstrap classpath registration occurs before any ASM or transformer class is loaded.
 */
public final class SunlitBootAgent {
    private static boolean initialized;

    private SunlitBootAgent() {
    }

    public static synchronized void premain(String agentArgs, Instrumentation inst) {
        // ServerStarterJar replays premain using its own Instrumentation instance after the JVM
        // already invoked this same system-loader class. Register only with the original instance.
        if (initialized) {
            return;
        }
        Module payloadModule = null;
        try {
            CodeSource cs = SunlitBootAgent.class.getProtectionDomain().getCodeSource();
            URL loc = cs != null ? cs.getLocation() : null;
            if (loc != null) {
                File jarFile = new File(loc.toURI());
                if (jarFile.isFile()) {
                    inst.appendToBootstrapClassLoaderSearch(new JarFile(jarFile));
                    Class<?> payloadCls = Class.forName("dev.sunlit.bootagent.payload.BootJarPayload", true, null);
                    payloadModule = payloadCls.getModule();
                }
            }
            Class<?> transformerCls = Class.forName("dev.sunlit.bootagent.SunlitBootTransformer", true, null);
            transformerCls.getMethod("install", Instrumentation.class, Module.class)
                    .invoke(null, inst, payloadModule);
            Class<?> extraCls = Class.forName("dev.sunlit.bootagent.SunlitExtraTransformer", true, null);
            extraCls.getMethod("install", Instrumentation.class, Module.class)
                    .invoke(null, inst, payloadModule);
        } catch (Throwable t) {
            System.err.println("[SunlitBootAgent] Initialization failed: " + t);
            t.printStackTrace(System.err);
        }
        initialized = true;
    }

    /**
     * Post-exit helper entry point invoked when {@code config/sunlit-boot.jar} is locked on
     * Windows by the active JVM's {@code -javaagent} handle.
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !"--sync".equals(args[0])) {
            throw new IllegalArgumentException("Usage: --sync <pid> <staged> <target> <sha256>");
        }
        long pid = Long.parseLong(args[1]);
        Path staged = Path.of(args[2]).toAbsolutePath().normalize();
        Path target = Path.of(args[3]).toAbsolutePath().normalize();
        String expectedSha = args[4];
        if (!expectedSha.matches("[a-f0-9]{64}")
                || Files.isSymbolicLink(staged)
                || Files.isSymbolicLink(target)
                || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        ProcessHandle.of(pid).ifPresent(ph -> {
            if (ph.isAlive()) {
                try {
                    ph.onExit().get(24, TimeUnit.HOURS);
                } catch (Exception ignored) {
                }
            }
        });
        if (!sha256File(staged).equals(expectedSha)) {
            return;
        }
        Path parent = target.getParent();
        if (parent == null || Files.isSymbolicLink(parent)) {
            return;
        }
        Files.createDirectories(parent);
        for (int attempt = 0; attempt < 25; attempt++) {
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && sha256File(target).equals(expectedSha)) {
                return;
            }
            Path tmp = parent.resolve(".sunlit-boot.jar.tmp-" + UUID.randomUUID());
            try {
                Files.copy(staged, tmp, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                Thread.sleep(200L);
            }
        }
    }

    private static String sha256File(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) != -1; ) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static byte[] transformResolver(ClassLoader loader, byte[] bytes) {
        return SunlitBootTransformer.transformResolver(loader, bytes);
    }

    public static byte[] transformZipFileSystem(ClassLoader loader, byte[] bytes) {
        return SunlitBootTransformer.transformZipFileSystem(loader, bytes);
    }

    public static byte[] transformZipFileSystemEntry(ClassLoader loader, byte[] bytes) {
        return SunlitBootTransformer.transformZipFileSystemEntry(loader, bytes);
    }

    public static byte[] transformUnionFileSystem(ClassLoader loader, byte[] bytes) {
        return SunlitBootTransformer.transformUnionFileSystem(loader, bytes);
    }

    public static byte[] transformUnionFileSystemProvider(byte[] bytes) {
        return SunlitBootTransformer.transformUnionFileSystemProvider(bytes);
    }

    public static byte[] transformJar(ClassLoader loader, byte[] bytes) {
        return SunlitBootTransformer.transformJar(loader, bytes);
    }

    public static byte[] transformModDirDiscoverer(ClassLoader loader, byte[] bytes) {
        return SunlitBootTransformer.transformModDirDiscoverer(loader, bytes);
    }

    public static byte[] transformAbstractJarLocator(byte[] bytes) {
        return SunlitBootTransformer.transformAbstractJarLocator(bytes);
    }

    public static byte[] transformAbstractModProvider(byte[] bytes) {
        return SunlitBootTransformer.transformAbstractModProvider(bytes);
    }

    public static byte[] transformBackgroundWaiter(byte[] bytes) {
        return SunlitBootTransformer.transformBackgroundWaiter(bytes);
    }

    public static byte[] transformBackgroundScanHandler(ClassLoader loader, byte[] bytes) {
        return SunlitBootTransformer.transformBackgroundScanHandler(loader, bytes);
    }

    public static byte[] transformMixinBytecode(ClassLoader loader, byte[] bytes) {
        return SunlitBootTransformer.transformMixinBytecode(loader, bytes);
    }

    public static byte[] transformMixinLaunchPlugin(byte[] bytes) {
        return SunlitBootTransformer.transformMixinLaunchPlugin(bytes);
    }

    public static byte[] transformMixinHandler(ClassLoader loader, byte[] bytes) {
        return SunlitBootTransformer.transformMixinHandler(loader, bytes);
    }

    public static byte[] transformMixinProcessor(ClassLoader loader, byte[] bytes) {
        return SunlitBootTransformer.transformMixinProcessor(loader, bytes);
    }

    public static byte[] transformDownloadUtils(ClassLoader loader, byte[] bytes) {
        return SunlitBootTransformer.transformDownloadUtils(loader, bytes);
    }

    public static byte[] transformPostProcessors(ClassLoader loader, byte[] bytes) {
        return SunlitBootTransformer.transformPostProcessors(loader, bytes);
    }

    public static byte[] transformModList(ClassLoader loader, byte[] bytes) {
        return SunlitBootTransformer.transformModList(loader, bytes);
    }

    public static byte[] transformModernUiOutlineFont(byte[] bytes) {
        return SunlitBootTransformer.transformModernUiOutlineFont(bytes);
    }

    public static byte[] widenClass(byte[] bytes, Set<String> fieldsToWiden, Set<String> invalidateMethods) {
        return SunlitBootTransformer.widenClass(bytes, fieldsToWiden, invalidateMethods);
    }
}
