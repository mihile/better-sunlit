package dev.sunlit.bettersunlit.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.caffeinemc.mods.sodium.api.vertex.attributes.common.ColorAttribute;
import net.caffeinemc.mods.sodium.api.vertex.attributes.common.LightAttribute;
import net.caffeinemc.mods.sodium.api.vertex.attributes.common.NormalAttribute;
import net.caffeinemc.mods.sodium.api.vertex.attributes.common.OverlayAttribute;
import net.caffeinemc.mods.sodium.api.vertex.attributes.common.PositionAttribute;
import net.caffeinemc.mods.sodium.api.vertex.attributes.common.TextureAttribute;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatDescription;
import net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex;
import org.embeddedt.embeddium.render.matrix_stack.CachingPoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Allocation-free helpers for hot EMF/vanilla cube compilation, stack pose caching,
 * and EMF math parser character iteration.
 */
public final class EmfRenderPerf {

    /**
     * Bridge interface implemented on {@code ModelPart$Cube} via Mixin + post-apply bytecode so
     * subclasses such as {@code EMFCube} can access {@code f_104341_} without compile-time visibility
     * on package-private {@code ModelPart$Polygon}.
     */
    public interface PolygonHolder {
        Object[] sunlit$polygons();

        default CompiledCube sunlit$compiledCube() {
            return null;
        }

        default void sunlit$setCompiledCube(CompiledCube compiled) {
        }
    }

    /**
     * Flattened immutable vertex/normal representation of a {@code ModelPart$Cube} or {@code EMFCube}
     * with positions already divided by 16.0F, avoiding per-frame {@code MethodHandle} dereferencing.
     */
    public static final class CompiledCube {
        static final CompiledCube EMPTY = new CompiledCube(new float[0], new float[0], 0, true);
        static final CompiledCube NON_QUAD = new CompiledCube(new float[0], new float[0], 0, false);

        final float[] quadVerts;
        final float[] quadNormals;
        final int quadCount;
        final boolean allQuads;

        private CompiledCube(float[] quadVerts, float[] quadNormals, int quadCount, boolean allQuads) {
            this.quadVerts = quadVerts;
            this.quadNormals = quadNormals;
            this.quadCount = quadCount;
            this.allQuads = allQuads;
        }
    }

    private static final boolean HAS_SODIUM_FAST_PATH;
    private static final int SODIUM_BATCH_QUADS = 32;

    private static final MethodHandle GET_POLY_NORMAL;
    private static final MethodHandle GET_POLY_VERTICES;
    private static final MethodHandle GET_VERT_POS;
    private static final MethodHandle GET_VERT_U;
    private static final MethodHandle GET_VERT_V;

    static {
        boolean hasSodium = false;
        try {
            ClassLoader cl = EmfRenderPerf.class.getClassLoader();
            Class.forName("net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter", false, cl);
            Class.forName("net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex", false, cl);
            Class.forName("org.lwjgl.system.MemoryStack", false, cl);
            hasSodium = true;
        } catch (Throwable ignored) {
            hasSodium = false;
        }
        HAS_SODIUM_FAST_PATH = hasSodium;

        try {
            Class<?> polygonClass = Class.forName("net.minecraft.client.model.geom.ModelPart$Polygon");
            Class<?> vertexClass = Class.forName("net.minecraft.client.model.geom.ModelPart$Vertex");
            GET_POLY_NORMAL = getterHandle(polygonClass, "f_104360_", Vector3f.class);
            GET_POLY_VERTICES = getterHandle(polygonClass, "f_104359_", Object[].class);
            GET_VERT_POS = getterHandle(vertexClass, "f_104371_", Vector3f.class);
            GET_VERT_U = getterHandle(vertexClass, "f_104372_", float.class);
            GET_VERT_V = getterHandle(vertexClass, "f_104373_", float.class);
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private EmfRenderPerf() {
    }

    private static MethodHandle getterHandle(Class<?> owner, String fieldName, Class<?> returnType) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            boolean allPublic = Modifier.isPublic(owner.getModifiers())
                    && Modifier.isPublic(field.getModifiers());
            MethodHandles.Lookup lookup;
            if (allPublic) {
                lookup = MethodHandles.publicLookup();
            } else {
                field.setAccessible(true);
                lookup = MethodHandles.lookup();
            }
            return lookup.unreflectGetter(field).asType(MethodType.methodType(returnType, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static CompiledCube getOrCompile(PolygonHolder holder) {
        CompiledCube cached = holder.sunlit$compiledCube();
        if (cached != null) {
            return cached;
        }
        Object[] polygons = holder.sunlit$polygons();
        if (polygons == null || polygons.length == 0) {
            holder.sunlit$setCompiledCube(CompiledCube.EMPTY);
            return CompiledCube.EMPTY;
        }
        int polyCount = polygons.length;
        float[] verts = new float[polyCount * 20];
        float[] norms = new float[polyCount * 3];
        int vOut = 0;
        int nOut = 0;
        try {
            for (Object polygon : polygons) {
                if (polygon == null) {
                    holder.sunlit$setCompiledCube(CompiledCube.NON_QUAD);
                    return CompiledCube.NON_QUAD;
                }
                Object[] vertices = (Object[]) GET_POLY_VERTICES.invokeExact(polygon);
                if (vertices == null || vertices.length != 4) {
                    holder.sunlit$setCompiledCube(CompiledCube.NON_QUAD);
                    return CompiledCube.NON_QUAD;
                }
                Vector3f polyNormal = (Vector3f) GET_POLY_NORMAL.invokeExact(polygon);
                norms[nOut++] = polyNormal.x();
                norms[nOut++] = polyNormal.y();
                norms[nOut++] = polyNormal.z();
                for (int i = 0; i < 4; i++) {
                    Object vertex = vertices[i];
                    Vector3f pos = (Vector3f) GET_VERT_POS.invokeExact(vertex);
                    verts[vOut++] = pos.x() / 16.0F;
                    verts[vOut++] = pos.y() / 16.0F;
                    verts[vOut++] = pos.z() / 16.0F;
                    verts[vOut++] = (float) GET_VERT_U.invokeExact(vertex);
                    verts[vOut++] = (float) GET_VERT_V.invokeExact(vertex);
                }
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        CompiledCube compiled = new CompiledCube(verts, norms, polyCount, true);
        holder.sunlit$setCompiledCube(compiled);
        return compiled;
    }

    /**
     * Compiles and submits all polygons of a cube using cached flat geometry and Emberbium's
     * native {@link VertexBufferWriter} batch path when supported by {@code consumer}, falling
     * back to allocation-free scalar emission otherwise.
     */
    public static void compileCube(
            PolygonHolder holder,
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int packedLight,
            int packedOverlay,
            float red,
            float green,
            float blue,
            float alpha) {
        if (holder == null) {
            return;
        }
        CompiledCube compiled = getOrCompile(holder);
        if (!compiled.allQuads) {
            compilePolygons(holder.sunlit$polygons(), pose, consumer, packedLight, packedOverlay, red, green, blue, alpha);
            return;
        }
        if (compiled.quadCount == 0) {
            return;
        }
        Matrix4f poseMat = pose.m_252922_();
        Matrix3f normMat = pose.m_252943_();
        if (HAS_SODIUM_FAST_PATH && SodiumFastPath.tryEmitQuads(
                compiled, poseMat, normMat, consumer, packedLight, packedOverlay, red, green, blue, alpha)) {
            return;
        }
        emitCompiledScalar(compiled, poseMat, normMat, consumer, packedLight, packedOverlay, red, green, blue, alpha);
    }

    private static void emitCompiledScalar(
            CompiledCube compiled,
            Matrix4f poseMat,
            Matrix3f normMat,
            VertexConsumer consumer,
            int packedLight,
            int packedOverlay,
            float red,
            float green,
            float blue,
            float alpha) {
        float m00 = poseMat.m00(), m01 = poseMat.m01(), m02 = poseMat.m02();
        float m10 = poseMat.m10(), m11 = poseMat.m11(), m12 = poseMat.m12();
        float m20 = poseMat.m20(), m21 = poseMat.m21(), m22 = poseMat.m22();
        float m30 = poseMat.m30(), m31 = poseMat.m31(), m32 = poseMat.m32();

        float n00 = normMat.m00(), n01 = normMat.m01(), n02 = normMat.m02();
        float n10 = normMat.m10(), n11 = normMat.m11(), n12 = normMat.m12();
        float n20 = normMat.m20(), n21 = normMat.m21(), n22 = normMat.m22();

        float[] verts = compiled.quadVerts;
        float[] norms = compiled.quadNormals;
        int quadCount = compiled.quadCount;
        int vIdx = 0;
        int nIdx = 0;
        for (int q = 0; q < quadCount; q++) {
            float onx = norms[nIdx];
            float ony = norms[nIdx + 1];
            float onz = norms[nIdx + 2];
            nIdx += 3;

            float nx = org.joml.Math.fma(n00, onx, org.joml.Math.fma(n10, ony, n20 * onz));
            float ny = org.joml.Math.fma(n01, onx, org.joml.Math.fma(n11, ony, n21 * onz));
            float nz = org.joml.Math.fma(n02, onx, org.joml.Math.fma(n12, ony, n22 * onz));

            for (int v = 0; v < 4; v++) {
                float vx = verts[vIdx];
                float vy = verts[vIdx + 1];
                float vz = verts[vIdx + 2];
                float u = verts[vIdx + 3];
                float vt = verts[vIdx + 4];
                vIdx += 5;

                float wx = org.joml.Math.fma(m00, vx, org.joml.Math.fma(m10, vy, org.joml.Math.fma(m20, vz, m30)));
                float wy = org.joml.Math.fma(m01, vx, org.joml.Math.fma(m11, vy, org.joml.Math.fma(m21, vz, m31)));
                float wz = org.joml.Math.fma(m02, vx, org.joml.Math.fma(m12, vy, org.joml.Math.fma(m22, vz, m32)));

                consumer.m_5954_(
                        wx, wy, wz,
                        red, green, blue, alpha,
                        u, vt,
                        packedOverlay, packedLight,
                        nx, ny, nz);
            }
        }
    }

    /**
     * Submits cached quad geometry directly through Sodium's {@link VertexBufferWriter} using the
     * supplied {@link VertexFormatDescription} (intended for runtime calls with {@link ModelVertex#FORMAT}
     * and offline verification tests).
     */
    public static boolean emitCompiledQuadsNative(
            CompiledCube compiled,
            PoseStack.Pose pose,
            VertexBufferWriter writer,
            VertexFormatDescription format,
            int packedLight,
            int packedOverlay,
            float red,
            float green,
            float blue,
            float alpha) {
        if (compiled == null || !compiled.allQuads || writer == null || format == null || !writer.canUseIntrinsics()) {
            return false;
        }
        if (compiled.quadCount == 0) {
            return true;
        }
        SodiumFastPath.emitQuads(
                compiled, pose.m_252922_(), pose.m_252943_(), writer, format,
                packedLight, packedOverlay, red, green, blue, alpha);
        return true;
    }

    private static final class ModelVertexFormatHolder {
        static final VertexFormatDescription FORMAT;

        static {
            VertexFormatDescription fmt = null;
            try {
                fmt = ModelVertex.FORMAT;
            } catch (Throwable ignored) {
                fmt = null;
            }
            FORMAT = fmt;
        }
    }

    private static final class SodiumFastPath {
        private static final int MODEL_VERTEX_STRIDE = 36;

        private SodiumFastPath() {
        }

        static boolean tryEmitQuads(
                CompiledCube cube,
                Matrix4f poseMat,
                Matrix3f normMat,
                VertexConsumer consumer,
                int packedLight,
                int packedOverlay,
                float red,
                float green,
                float blue,
                float alpha) {
            VertexFormatDescription fmt = ModelVertexFormatHolder.FORMAT;
            if (fmt == null) {
                return false;
            }
            VertexBufferWriter writer = VertexBufferWriter.tryOf(consumer);
            if (writer == null) {
                return false;
            }
            emitQuads(cube, poseMat, normMat, writer, fmt, packedLight, packedOverlay, red, green, blue, alpha);
            return true;
        }

        static void emitQuads(
                CompiledCube cube,
                Matrix4f poseMat,
                Matrix3f normMat,
                VertexBufferWriter writer,
                VertexFormatDescription format,
                int packedLight,
                int packedOverlay,
                float red,
                float green,
                float blue,
                float alpha) {
            int quadCount = cube.quadCount;
            int colorAbgr = ColorABGR.pack(red, green, blue, alpha);
            float[] verts = cube.quadVerts;
            float[] norms = cube.quadNormals;

            float m00 = poseMat.m00(), m01 = poseMat.m01(), m02 = poseMat.m02();
            float m10 = poseMat.m10(), m11 = poseMat.m11(), m12 = poseMat.m12();
            float m20 = poseMat.m20(), m21 = poseMat.m21(), m22 = poseMat.m22();
            float m30 = poseMat.m30(), m31 = poseMat.m31(), m32 = poseMat.m32();

            float n00 = normMat.m00(), n01 = normMat.m01(), n02 = normMat.m02();
            float n10 = normMat.m10(), n11 = normMat.m11(), n12 = normMat.m12();
            float n20 = normMat.m20(), n21 = normMat.m21(), n22 = normMat.m22();

            for (int qStart = 0; qStart < quadCount; qStart += SODIUM_BATCH_QUADS) {
                int batchQuads = Math.min(SODIUM_BATCH_QUADS, quadCount - qStart);
                int vertexCount = batchQuads << 2;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    long ptr = stack.nmalloc(vertexCount * MODEL_VERTEX_STRIDE);
                    long cur = ptr;
                    int vIdx = qStart * 20;
                    int nIdx = qStart * 3;
                    for (int q = 0; q < batchQuads; q++) {
                        float onx = norms[nIdx];
                        float ony = norms[nIdx + 1];
                        float onz = norms[nIdx + 2];
                        nIdx += 3;

                        float nx = org.joml.Math.fma(n00, onx, org.joml.Math.fma(n10, ony, n20 * onz));
                        float ny = org.joml.Math.fma(n01, onx, org.joml.Math.fma(n11, ony, n21 * onz));
                        float nz = org.joml.Math.fma(n02, onx, org.joml.Math.fma(n12, ony, n22 * onz));
                        int normPacked = NormI8.pack(nx, ny, nz);

                        for (int v = 0; v < 4; v++) {
                            float vx = verts[vIdx];
                            float vy = verts[vIdx + 1];
                            float vz = verts[vIdx + 2];
                            float u = verts[vIdx + 3];
                            float vt = verts[vIdx + 4];
                            vIdx += 5;

                            float wx = org.joml.Math.fma(m00, vx, org.joml.Math.fma(m10, vy, org.joml.Math.fma(m20, vz, m30)));
                            float wy = org.joml.Math.fma(m01, vx, org.joml.Math.fma(m11, vy, org.joml.Math.fma(m21, vz, m31)));
                            float wz = org.joml.Math.fma(m02, vx, org.joml.Math.fma(m12, vy, org.joml.Math.fma(m22, vz, m32)));

                            PositionAttribute.put(cur, wx, wy, wz);
                            ColorAttribute.set(cur + 12L, colorAbgr);
                            TextureAttribute.put(cur + 16L, u, vt);
                            OverlayAttribute.set(cur + 24L, packedOverlay);
                            LightAttribute.set(cur + 28L, packedLight);
                            NormalAttribute.set(cur + 32L, normPacked);
                            cur += MODEL_VERTEX_STRIDE;
                        }
                    }
                    writer.push(stack, ptr, vertexCount, format);
                }
            }
        }
    }

    /**
     * Scalar-transforms polygon normals and vertex positions directly without allocating
     * per-polygon {@link Vector3f} or per-vertex {@link org.joml.Vector4f} temporaries.
     */
    public static void compilePolygons(
            Object[] polygons,
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int packedLight,
            int packedOverlay,
            float red,
            float green,
            float blue,
            float alpha) {
        if (polygons == null || polygons.length == 0) {
            return;
        }
        Matrix4f poseMat = pose.m_252922_();
        Matrix3f normMat = pose.m_252943_();

        float m00 = poseMat.m00();
        float m01 = poseMat.m01();
        float m02 = poseMat.m02();
        float m10 = poseMat.m10();
        float m11 = poseMat.m11();
        float m12 = poseMat.m12();
        float m20 = poseMat.m20();
        float m21 = poseMat.m21();
        float m22 = poseMat.m22();
        float m30 = poseMat.m30();
        float m31 = poseMat.m31();
        float m32 = poseMat.m32();

        float n00 = normMat.m00();
        float n01 = normMat.m01();
        float n02 = normMat.m02();
        float n10 = normMat.m10();
        float n11 = normMat.m11();
        float n12 = normMat.m12();
        float n20 = normMat.m20();
        float n21 = normMat.m21();
        float n22 = normMat.m22();

        try {
            for (Object polygon : polygons) {
                Vector3f polyNormal = (Vector3f) GET_POLY_NORMAL.invokeExact(polygon);
                float onx = polyNormal.x();
                float ony = polyNormal.y();
                float onz = polyNormal.z();

                float nx = org.joml.Math.fma(n00, onx, org.joml.Math.fma(n10, ony, n20 * onz));
                float ny = org.joml.Math.fma(n01, onx, org.joml.Math.fma(n11, ony, n21 * onz));
                float nz = org.joml.Math.fma(n02, onx, org.joml.Math.fma(n12, ony, n22 * onz));

                Object[] vertices = (Object[]) GET_POLY_VERTICES.invokeExact(polygon);
                for (Object vertex : vertices) {
                    Vector3f pos = (Vector3f) GET_VERT_POS.invokeExact(vertex);
                    float vx = pos.x() / 16.0F;
                    float vy = pos.y() / 16.0F;
                    float vz = pos.z() / 16.0F;

                    float wx = org.joml.Math.fma(m00, vx, org.joml.Math.fma(m10, vy, org.joml.Math.fma(m20, vz, m30)));
                    float wy = org.joml.Math.fma(m01, vx, org.joml.Math.fma(m11, vy, org.joml.Math.fma(m21, vz, m31)));
                    float wz = org.joml.Math.fma(m02, vx, org.joml.Math.fma(m12, vy, org.joml.Math.fma(m22, vz, m32)));

                    consumer.m_5954_(
                            wx, wy, wz,
                            red, green, blue, alpha,
                            (float) GET_VERT_U.invokeExact(vertex),
                            (float) GET_VERT_V.invokeExact(vertex),
                            packedOverlay, packedLight,
                            nx, ny, nz);
                }
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Enables Embeddium pose-stack matrix caching on push and restores it on pop.
     */
    public static void pushWithPoseCaching(PoseStack poseStack) {
        if (poseStack instanceof CachingPoseStack caching) {
            caching.embeddium$setCachingEnabled(true);
        }
        poseStack.m_85836_();
    }

    public static void popWithPoseCaching(PoseStack poseStack) {
        poseStack.m_85849_();
        if (poseStack instanceof CachingPoseStack caching) {
            caching.embeddium$setCachingEnabled(false);
        }
    }

    /**
     * Direct {@code Character} iterator over a {@link String} replacing
     * {@code str.chars().mapToObj(c -> (char) c).iterator()} pipeline allocations.
     */
    /**
     * Zero-allocation replacement for {@code [a-zA-Z0-9_]+\.([trs][xyz]$|visible$|visible_boxes$)}
     * used in {@code ModelPartVariableFactory.createsThisVariable}.
     */
    public static boolean matchesModelPartVariableSyntax(String s) {
        if (s == null) {
            return false;
        }
        int dot = s.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        for (int i = 0; i < dot; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_';
            if (!ok) {
                return false;
            }
        }
        int start = dot + 1;
        int suffixLen = s.length() - start;
        if (suffixLen == 2) {
            char c0 = s.charAt(start);
            char c1 = s.charAt(start + 1);
            return (c0 == 't' || c0 == 'r' || c0 == 's') && (c1 == 'x' || c1 == 'y' || c1 == 'z');
        }
        if (suffixLen == 7) {
            return s.regionMatches(start, "visible", 0, 7);
        }
        if (suffixLen == 13) {
            return s.regionMatches(start, "visible_boxes", 0, 13);
        }
        return false;
    }

    public static Iterator<Character> charIterator(String expr) {
        return new CharSequenceIterator(expr);
    }

    private static final class CharSequenceIterator implements Iterator<Character> {
        private final String s;
        private final int length;
        private int index;

        private CharSequenceIterator(String s) {
            this.s = s;
            this.length = s.length();
        }

        @Override
        public boolean hasNext() {
            return index < length;
        }

        @Override
        public Character next() {
            if (index >= length) {
                throw new NoSuchElementException();
            }
            return s.charAt(index++);
        }
    }

    public static final net.minecraft.client.model.geom.ModelPart[] EMPTY_MODEL_PARTS =
            new net.minecraft.client.model.geom.ModelPart[0];

    private static final int MAX_LOD_TIMERS = 4096;
    private static final int LOD_TIMER_TRIM_SIZE = 256;
    private static final Object LOD_TIMER_LOCK = new Object();
    private static final it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<LodTimerKey> LOD_TIMERS =
            new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();
    private static final LodTimerKey LOOKUP_KEY = new LodTimerKey();

    static {
        LOD_TIMERS.defaultReturnValue(Integer.MIN_VALUE);
    }

    public static int getLodTimer(java.util.UUID uuid, String modelId) {
        synchronized (LOD_TIMER_LOCK) {
            LOOKUP_KEY.set(uuid, modelId);
            return LOD_TIMERS.getInt(LOOKUP_KEY);
        }
    }

    public static void putLodTimer(java.util.UUID uuid, String modelId, int timer) {
        synchronized (LOD_TIMER_LOCK) {
            LOOKUP_KEY.set(uuid, modelId);
            if (LOD_TIMERS.replace(LOOKUP_KEY, timer) == Integer.MIN_VALUE) {
                if (LOD_TIMERS.size() >= MAX_LOD_TIMERS) {
                    LOD_TIMERS.clear();
                    LOD_TIMERS.trim(LOD_TIMER_TRIM_SIZE);
                }
                LOD_TIMERS.put(new LodTimerKey(uuid, modelId), timer);
            }
        }
    }

    /** Clears cached per-entity LOD countdowns (e.g. on world/dimension change). */
    public static void clearLodTimers() {
        synchronized (LOD_TIMER_LOCK) {
            if (!LOD_TIMERS.isEmpty()) {
                LOD_TIMERS.clear();
                LOD_TIMERS.trim(LOD_TIMER_TRIM_SIZE);
            }
        }
    }

    public static void clearLodTimersForTests() {
        clearLodTimers();
    }

    private static final class LodTimerKey {
        private java.util.UUID uuid;
        private String modelId;
        private int hash;

        LodTimerKey() {
        }

        LodTimerKey(java.util.UUID uuid, String modelId) {
            set(uuid, modelId);
        }

        void set(java.util.UUID uuid, String modelId) {
            this.uuid = uuid;
            this.modelId = modelId;
            int h = java.util.Objects.hashCode(uuid);
            h = 31 * h + java.util.Objects.hashCode(modelId);
            this.hash = h;
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof LodTimerKey other)) {
                return false;
            }
            return java.util.Objects.equals(this.uuid, other.uuid)
                    && java.util.Objects.equals(this.modelId, other.modelId);
        }
    }
}
