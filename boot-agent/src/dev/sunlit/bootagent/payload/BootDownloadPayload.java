package dev.sunlit.bootagent.payload;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Self-contained JDK-only SHA-1 and post-processor output stamp cache template copied by ASM into
 * Forge Installer's {@code DownloadUtils} and {@code PostProcessors} inside ForgeWrapper's
 * isolated {@code URLClassLoader}.
 */
public final class BootDownloadPayload {
    private static volatile Properties sunlitShaProps;

    private BootDownloadPayload() {
    }

    public static String sunlitCachedGetSha1(File file) {
        if (file == null) {
            return null;
        }
        long len = file.length();
        if (len <= 0L) {
            return sunlitRawSha1(file);
        }
        long mtime = file.lastModified();
        String key = file.getAbsolutePath();
        String stamp = Long.toString(len) + ':' + Long.toString(mtime);
        Properties props = sunlitGetProps();
        String existing = props.getProperty(key);
        if (existing != null) {
            int sep = existing.indexOf('|');
            if (sep > 0 && stamp.length() == sep && existing.regionMatches(0, stamp, 0, sep)) {
                return existing.substring(sep + 1);
            }
        }
        String computed = sunlitRawSha1(file);
        if (computed != null) {
            synchronized (BootDownloadPayload.class) {
                props.setProperty(key, stamp + '|' + computed);
                sunlitPersistProps(props);
            }
        }
        return computed;
    }

    private static Properties sunlitGetProps() {
        Properties props = sunlitShaProps;
        if (props != null) {
            return props;
        }
        synchronized (BootDownloadPayload.class) {
            props = sunlitShaProps;
            if (props != null) {
                return props;
            }
            props = new Properties();
            File cacheFile = new File("config/.sunlit-installer-sha1.cache");
            if (cacheFile.isFile()) {
                try (FileInputStream fis = new FileInputStream(cacheFile)) {
                    props.load(fis);
                } catch (Throwable ignored) {
                }
            }
            sunlitShaProps = props;
            return props;
        }
    }

    private static void sunlitPersistProps(Properties props) {
        File cfgDir = new File("config");
        if (!cfgDir.isDirectory()) {
            return;
        }
        File cacheFile = new File(cfgDir, ".sunlit-installer-sha1.cache");
        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
            props.store(fos, null);
        } catch (Throwable ignored) {
        }
    }

    private static String sunlitRawSha1(File file) {
        return null;
    }

    public Set<File> sunlitCachedProcess(File librariesDir, File minecraftJar, File outputDir, File installerJar) {
        if (librariesDir != null && minecraftJar != null && outputDir != null && installerJar != null) {
            long instLen = installerJar.length();
            long mcLen = minecraftJar.length();
            if (instLen > 0L && mcLen > 0L) {
                long instMtime = installerJar.lastModified();
                long mcMtime = minecraftJar.lastModified();
                String key = "proc|" + librariesDir.getAbsolutePath() + '|'
                        + minecraftJar.getAbsolutePath() + '|' + installerJar.getAbsolutePath();
                String inputStamp = Long.toString(instLen) + ':' + Long.toString(instMtime) + ':'
                        + Long.toString(mcLen) + ':' + Long.toString(mcMtime);
                Properties props = sunlitGetProps();
                String cached = props.getProperty(key);
                if (cached != null) {
                    int bar = cached.indexOf('|');
                    if (bar > 0 && inputStamp.length() == bar && cached.regionMatches(0, inputStamp, 0, bar)) {
                        Set<File> verified = new HashSet<>();
                        boolean valid = true;
                        int pos = bar + 1;
                        int end = cached.length();
                        if (pos >= end) {
                            valid = false;
                        }
                        while (valid && pos < end) {
                            int nextBar = cached.indexOf('|', pos);
                            String item = nextBar >= 0 ? cached.substring(pos, nextBar) : cached.substring(pos);
                            int q = item.lastIndexOf('?');
                            int colon = q > 0 ? item.indexOf(':', q + 1) : -1;
                            if (q <= 0 || colon <= q + 1 || colon + 1 >= item.length()) {
                                valid = false;
                                break;
                            }
                            try {
                                String path = item.substring(0, q);
                                long expectedLen = Long.parseLong(item.substring(q + 1, colon));
                                long expectedMtime = Long.parseLong(item.substring(colon + 1));
                                File outFile = new File(path);
                                if (!outFile.isFile() || outFile.length() != expectedLen || outFile.lastModified() != expectedMtime) {
                                    valid = false;
                                    break;
                                }
                                verified.add(outFile);
                            } catch (Throwable ignored) {
                                valid = false;
                                break;
                            }
                            pos = nextBar >= 0 ? nextBar + 1 : end;
                        }
                        if (valid && !verified.isEmpty()) {
                            return verified;
                        }
                    }
                }
                Set<File> computed = this.sunlitRawProcess(librariesDir, minecraftJar, outputDir, installerJar);
                if (computed != null && !computed.isEmpty()) {
                    StringBuilder sb = new StringBuilder(inputStamp);
                    boolean serializable = true;
                    for (File outFile : computed) {
                        if (outFile == null || !outFile.isFile()) {
                            serializable = false;
                            break;
                        }
                        String path = outFile.getAbsolutePath();
                        if (path.indexOf('|') >= 0 || path.indexOf('?') >= 0) {
                            serializable = false;
                            break;
                        }
                        sb.append('|').append(path).append('?')
                                .append(outFile.length()).append(':').append(outFile.lastModified());
                    }
                    if (serializable) {
                        synchronized (BootDownloadPayload.class) {
                            props.setProperty(key, sb.toString());
                            sunlitPersistProps(props);
                        }
                    }
                }
                return computed;
            }
        }
        return this.sunlitRawProcess(librariesDir, minecraftJar, outputDir, installerJar);
    }

    private Set<File> sunlitRawProcess(File librariesDir, File minecraftJar, File outputDir, File installerJar) {
        return null;
    }
}
