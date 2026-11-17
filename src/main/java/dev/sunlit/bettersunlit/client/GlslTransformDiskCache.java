package dev.sunlit.bettersunlit.client;

import dev.djefrey.colorwheel.compile.transform.ClrwlTransformOutput;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.ComputeParameters;
import net.irisshaders.iris.pipeline.transform.parameter.DHParameters;
import net.irisshaders.iris.pipeline.transform.parameter.GeometryInfoParameters;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import net.irisshaders.iris.pipeline.transform.parameter.SodiumParameters;
import net.irisshaders.iris.pipeline.transform.parameter.TextureStageParameters;
import net.irisshaders.iris.pipeline.transform.parameter.VanillaParameters;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic, SHA-256 keyed in-memory + disk cache for Iris {@code TransformPatcher} and
 * Colorwheel {@code ClrwlTransformPatcher} GLSL AST transformations.
 *
 * <p>Preloads saved entries on a background thread during startup and persists misses
 * asynchronously so Render-thread shader pipeline creation avoids repeating multi-second ANTLR
 * parsing across launches.
 */
public final class GlslTransformDiskCache {
    private static final Logger LOG = LoggerFactory.getLogger("sunlit_fish_visuals");
    private static final int MAGIC = 0x534C4731; // "SLG1"
    private static final int KIND_IRIS_MAP = 1;
    private static final int KIND_CLRWL_STR = 2;
    private static final int KIND_CLRWL_FRAG = 3;
    private static final int MAX_MEMORY_ENTRIES = 1024;
    private static final int WRITE_QUEUE_CAPACITY = 512;

    private static final ConcurrentHashMap<String, Map<PatchShaderType, String>> IRIS_MEM = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> CLRWL_STR_MEM = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ClrwlTransformOutput> CLRWL_FRAG_MEM = new ConcurrentHashMap<>();

    private static final BlockingQueue<WriteOp> WRITE_QUEUE = new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY);
    private static final AtomicBoolean WRITER_STARTED = new AtomicBoolean();
    private static final AtomicBoolean PRELOADED = new AtomicBoolean();

    private static volatile Path overrideCacheDir;

    private record WriteOp(String key, int kind, Object payload) {}

    private GlslTransformDiskCache() {}

    public static void setCacheDirForTests(Path dir) {
        overrideCacheDir = dir;
        IRIS_MEM.clear();
        CLRWL_STR_MEM.clear();
        CLRWL_FRAG_MEM.clear();
        PRELOADED.set(false);
    }

    public static Path cacheDir() {
        Path override = overrideCacheDir;
        if (override != null) {
            return override;
        }
        try {
            return FMLPaths.GAMEDIR.get().resolve(".better-sunlit-cache").resolve("glsl-v1");
        } catch (Throwable ignored) {
            return Path.of(".better-sunlit-cache", "glsl-v1");
        }
    }

    public static void preload() {
        if (!PRELOADED.compareAndSet(false, true)) {
            return;
        }
        Path dir = cacheDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        int loaded = 0;
        try (var stream = Files.newDirectoryStream(dir, "*.bin")) {
            for (Path file : stream) {
                if (IRIS_MEM.size() + CLRWL_STR_MEM.size() + CLRWL_FRAG_MEM.size() >= MAX_MEMORY_ENTRIES) {
                    break;
                }
                String fileName = file.getFileName().toString();
                if (fileName.length() <= 4) {
                    continue;
                }
                String key = fileName.substring(0, fileName.length() - 4);
                if (loadFileIntoMemory(key, file)) {
                    loaded++;
                }
            }
        } catch (IOException e) {
            LOG.debug("[BetterSunlit-GLSLCache] Preload scan failed: {}", e.toString());
        }
        if (loaded > 0) {
            LOG.info("[BetterSunlit-GLSLCache] Preloaded {} cached GLSL transforms", loaded);
        }
    }

    public static String keyForIris(
            String name,
            Map<PatchShaderType, String> inputs,
            Parameters parameters,
            boolean debugOptions) {
        if (inputs == null || parameters == null) {
            return null;
        }
        MessageDigest md = sha256();
        writeStr(md, "iris-v1");
        writeBool(md, debugOptions);
        writeStr(md, name);
        for (PatchShaderType type : PatchShaderType.values()) {
            writeInt(md, type.ordinal());
            writeStr(md, inputs.get(type));
        }
        if (!hashParameters(md, parameters)) {
            return null;
        }
        return toHex(md.digest());
    }

    public static String keyForClrwlString(
            String kind,
            String source,
            boolean isShadow,
            int gbufferType,
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap) {
        if (source == null) {
            return null;
        }
        MessageDigest md = sha256();
        writeStr(md, "clrwl-str-v1");
        writeStr(md, kind);
        writeStr(md, source);
        writeBool(md, isShadow);
        writeInt(md, gbufferType);
        hashTextureMap(md, textureMap);
        return toHex(md.digest());
    }

    public static String keyForClrwlFragment(
            String source,
            boolean isShadow,
            boolean isSolid,
            int gbufferType,
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap) {
        if (source == null) {
            return null;
        }
        MessageDigest md = sha256();
        writeStr(md, "clrwl-frag-v1");
        writeStr(md, source);
        writeBool(md, isShadow);
        writeBool(md, isSolid);
        writeInt(md, gbufferType);
        hashTextureMap(md, textureMap);
        return toHex(md.digest());
    }

    public static EnumMap<PatchShaderType, String> getIris(String key) {
        if (key == null) {
            return null;
        }
        Map<PatchShaderType, String> mem = IRIS_MEM.get(key);
        if (mem != null) {
            return copyEnumMap(mem);
        }
        Path file = cacheDir().resolve(key + ".bin");
        if (Files.isRegularFile(file) && loadFileIntoMemory(key, file)) {
            mem = IRIS_MEM.get(key);
            if (mem != null) {
                return copyEnumMap(mem);
            }
        }
        return null;
    }

    public static void putIris(String key, Map<PatchShaderType, String> result) {
        if (key == null || result == null) {
            return;
        }
        EnumMap<PatchShaderType, String> snapshot = copyEnumMap(result);
        if (IRIS_MEM.size() < MAX_MEMORY_ENTRIES) {
            IRIS_MEM.putIfAbsent(key, snapshot);
        }
        enqueueWrite(new WriteOp(key, KIND_IRIS_MAP, snapshot));
    }

    public static String getClrwlString(String key) {
        if (key == null) {
            return null;
        }
        String mem = CLRWL_STR_MEM.get(key);
        if (mem != null) {
            return mem;
        }
        Path file = cacheDir().resolve(key + ".bin");
        if (Files.isRegularFile(file) && loadFileIntoMemory(key, file)) {
            return CLRWL_STR_MEM.get(key);
        }
        return null;
    }

    public static void putClrwlString(String key, String result) {
        if (key == null || result == null) {
            return;
        }
        if (CLRWL_STR_MEM.size() < MAX_MEMORY_ENTRIES) {
            CLRWL_STR_MEM.putIfAbsent(key, result);
        }
        enqueueWrite(new WriteOp(key, KIND_CLRWL_STR, result));
    }

    public static ClrwlTransformOutput getClrwlFragment(String key) {
        if (key == null) {
            return null;
        }
        ClrwlTransformOutput mem = CLRWL_FRAG_MEM.get(key);
        if (mem != null) {
            return mem;
        }
        Path file = cacheDir().resolve(key + ".bin");
        if (Files.isRegularFile(file) && loadFileIntoMemory(key, file)) {
            return CLRWL_FRAG_MEM.get(key);
        }
        return null;
    }

    public static void putClrwlFragment(String key, ClrwlTransformOutput result) {
        if (key == null || result == null) {
            return;
        }
        Map<Integer, String> outputsCopy = result.outputs() == null
                ? Map.of()
                : Map.copyOf(result.outputs());
        ClrwlTransformOutput snapshot = new ClrwlTransformOutput(result.code(), outputsCopy);
        if (CLRWL_FRAG_MEM.size() < MAX_MEMORY_ENTRIES) {
            CLRWL_FRAG_MEM.putIfAbsent(key, snapshot);
        }
        enqueueWrite(new WriteOp(key, KIND_CLRWL_FRAG, snapshot));
    }

    public static void flushWritesForTests() throws InterruptedException {
        // An empty queue can still have its last write in flight. A FIFO barrier waits for
        // that write to finish before a test clears memory or switches the cache directory.
        ensureWriterStarted();
        var barrier = new java.util.concurrent.CountDownLatch(1);
        WRITE_QUEUE.put(new WriteOp("", 0, barrier));
        if (!barrier.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out flushing GLSL cache writes");
        }
    }

    private static EnumMap<PatchShaderType, String> copyEnumMap(Map<PatchShaderType, String> src) {
        EnumMap<PatchShaderType, String> copy = new EnumMap<>(PatchShaderType.class);
        copy.putAll(src);
        return copy;
    }

    private static void enqueueWrite(WriteOp op) {
        ensureWriterStarted();
        WRITE_QUEUE.offer(op);
    }

    private static void ensureWriterStarted() {
        if (!WRITER_STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    WriteOp op = WRITE_QUEUE.take();
                    if (op.kind == 0) {
                        ((java.util.concurrent.CountDownLatch) op.payload).countDown();
                    } else {
                        writeEntry(op);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable ignored) {
                }
            }
        }, "BetterSunlit-GLSL-CacheWriter");
        t.setDaemon(true);
        t.start();
    }

    private static void writeEntry(WriteOp op) {
        Path dir = cacheDir();
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(op.key + ".bin");
            if (Files.exists(target)) {
                return;
            }
            Path tmp = dir.resolve(op.key + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(MAGIC);
                out.writeByte(op.kind);
                switch (op.kind) {
                    case KIND_IRIS_MAP -> {
                        @SuppressWarnings("unchecked")
                        Map<PatchShaderType, String> map = (Map<PatchShaderType, String>) op.payload;
                        out.writeInt(map.size());
                        for (Map.Entry<PatchShaderType, String> e : map.entrySet()) {
                            out.writeInt(e.getKey().ordinal());
                            writeNullableUtf(out, e.getValue());
                        }
                    }
                    case KIND_CLRWL_STR -> writeNullableUtf(out, (String) op.payload);
                    case KIND_CLRWL_FRAG -> {
                        ClrwlTransformOutput frag = (ClrwlTransformOutput) op.payload;
                        writeNullableUtf(out, frag.code());
                        Map<Integer, String> outputs = frag.outputs();
                        out.writeInt(outputs.size());
                        for (Map.Entry<Integer, String> e : outputs.entrySet()) {
                            out.writeInt(e.getKey());
                            writeNullableUtf(out, e.getValue());
                        }
                    }
                    default -> {
                        Files.deleteIfExists(tmp);
                        return;
                    }
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.debug("[BetterSunlit-GLSLCache] Write failed for {}: {}", op.key, e.toString());
        }
    }

    private static boolean loadFileIntoMemory(String key, Path file) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) {
                return false;
            }
            int kind = in.readUnsignedByte();
            switch (kind) {
                case KIND_IRIS_MAP -> {
                    int size = in.readInt();
                    if (size < 0 || size > PatchShaderType.values().length) {
                        return false;
                    }
                    PatchShaderType[] types = PatchShaderType.values();
                    EnumMap<PatchShaderType, String> map = new EnumMap<>(PatchShaderType.class);
                    for (int i = 0; i < size; i++) {
                        int ord = in.readInt();
                        if (ord < 0 || ord >= types.length) {
                            return false;
                        }
                        String val = readNullableUtf(in);
                        map.put(types[ord], val);
                    }
                    IRIS_MEM.putIfAbsent(key, map);
                    return true;
                }
                case KIND_CLRWL_STR -> {
                    String val = readNullableUtf(in);
                    if (val == null) {
                        return false;
                    }
                    CLRWL_STR_MEM.putIfAbsent(key, val);
                    return true;
                }
                case KIND_CLRWL_FRAG -> {
                    String code = readNullableUtf(in);
                    if (code == null) {
                        return false;
                    }
                    int count = in.readInt();
                    if (count < 0 || count > 64) {
                        return false;
                    }
                    Map<Integer, String> outputs = new HashMap<>(count * 2 + 1);
                    for (int i = 0; i < count; i++) {
                        int k = in.readInt();
                        String v = readNullableUtf(in);
                        outputs.put(k, v);
                    }
                    CLRWL_FRAG_MEM.putIfAbsent(key, new ClrwlTransformOutput(code, Map.copyOf(outputs)));
                    return true;
                }
                default -> {
                    return false;
                }
            }
        } catch (EOFException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeNullableUtf(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readNullableUtf(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) {
            return null;
        }
        if (len > 16 * 1024 * 1024) {
            throw new IOException("Corrupt GLSL entry size: " + len);
        }
        byte[] bytes = in.readNBytes(len);
        if (bytes.length != len) {
            throw new EOFException("Truncated GLSL cache entry");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean hashParameters(MessageDigest md, Parameters p) {
        writeStr(md, p.getClass().getName());
        writeInt(md, p.patch != null ? p.patch.ordinal() : -1);
        hashTextureMap(md, p.getTextureMap());
        if (p instanceof GeometryInfoParameters g) {
            writeBool(md, g.hasGeometry);
            writeBool(md, g.hasTesselation);
            if (p instanceof VanillaParameters v) {
                hashAlpha(md, v.alpha);
                hashInputs(md, v.inputs);
                writeBool(md, v.hasChunkOffset);
                writeBool(md, v.isLines());
                return true;
            }
            return p.getClass() == GeometryInfoParameters.class;
        }
        if (p instanceof SodiumParameters s) {
            hashAlpha(md, s.alpha);
            hashInputs(md, s.inputs);
            return true;
        }
        if (p instanceof TextureStageParameters ts) {
            TextureStage stage = ts.getTextureStage();
            writeInt(md, stage != null ? stage.ordinal() : -1);
            return p.getClass() == TextureStageParameters.class || p instanceof ComputeParameters;
        }
        if (p instanceof DHParameters dh) {
            TextureStage stage = dh.getTextureStage();
            writeInt(md, stage != null ? stage.ordinal() : -1);
            return true;
        }
        return false;
    }

    private static void hashAlpha(MessageDigest md, AlphaTest alpha) {
        if (alpha == null) {
            writeInt(md, -1);
            return;
        }
        writeInt(md, alpha.function() != null ? alpha.function().ordinal() : -1);
        writeInt(md, Float.floatToRawIntBits(alpha.reference()));
    }

    private static void hashInputs(MessageDigest md, ShaderAttributeInputs inputs) {
        if (inputs == null) {
            writeInt(md, -1);
            return;
        }
        int mask = 0;
        if (inputs.hasColor()) mask |= 1;
        if (inputs.hasTex()) mask |= 2;
        if (inputs.hasOverlay()) mask |= 4;
        if (inputs.hasLight()) mask |= 8;
        if (inputs.hasNormal()) mask |= 16;
        if (inputs.isNewLines()) mask |= 32;
        if (inputs.isGlint()) mask |= 64;
        if (inputs.isText()) mask |= 128;
        writeInt(md, mask);
    }

    private static void hashTextureMap(
            MessageDigest md,
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> map) {
        if (map == null || map.isEmpty()) {
            writeInt(md, 0);
            return;
        }
        List<Map.Entry<Tri<String, TextureType, TextureStage>, String>> entries =
                new ArrayList<>(map.object2ObjectEntrySet());
        entries.sort(Comparator.comparing((Map.Entry<Tri<String, TextureType, TextureStage>, String> e) ->
                        e.getKey().first() != null ? e.getKey().first() : "")
                .thenComparingInt(e -> e.getKey().second() != null ? e.getKey().second().ordinal() : -1)
                .thenComparingInt(e -> e.getKey().third() != null ? e.getKey().third().ordinal() : -1)
                .thenComparing(e -> e.getValue() != null ? e.getValue() : ""));
        writeInt(md, entries.size());
        for (var e : entries) {
            Tri<String, TextureType, TextureStage> k = e.getKey();
            writeStr(md, k.first());
            writeInt(md, k.second() != null ? k.second().ordinal() : -1);
            writeInt(md, k.third() != null ? k.third().ordinal() : -1);
            writeStr(md, e.getValue());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeBool(MessageDigest md, boolean v) {
        md.update((byte) (v ? 1 : 0));
    }

    private static void writeInt(MessageDigest md, int v) {
        md.update((byte) (v >>> 24));
        md.update((byte) (v >>> 16));
        md.update((byte) (v >>> 8));
        md.update((byte) v);
    }

    private static void writeStr(MessageDigest md, String s) {
        if (s == null) {
            writeInt(md, -1);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeInt(md, b.length);
        md.update(b);
    }

    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(hex);
    }
}
