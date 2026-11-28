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
        static final CompiledCube EMPTY = new CompiledCube(new float[0], new float[0], new float[0], 0, true);
        static final CompiledCube NON_QUAD = new CompiledCube(new float[0], new float[0], new float[0], 0, false);

        final float[] quadVerts;
        final float[] quadNormals;
        /** 6 floats per quad: midU, midV, objTangentX, objTangentY, objTangentZ, objHandedness. */
        final float[] quadIrisMeta;
        final int quadCount;
        final boolean allQuads;

        private CompiledCube(
                float[] quadVerts,
                float[] quadNormals,
                float[] quadIrisMeta,
                int quadCount,
                boolean allQuads) {
            this.quadVerts = quadVerts;
            this.quadNormals = quadNormals;
            this.quadIrisMeta = quadIrisMeta;
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
        float[] irisMeta = new float[polyCount * 6];
        int vOut = 0;
        int nOut = 0;
        int mOut = 0;
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
                float onx = polyNormal.x();
                float ony = polyNormal.y();
                float onz = polyNormal.z();
                norms[nOut++] = onx;
                norms[nOut++] = ony;
                norms[nOut++] = onz;
                int qBase = vOut;
                for (int i = 0; i < 4; i++) {
                    Object vertex = vertices[i];
                    Vector3f pos = (Vector3f) GET_VERT_POS.invokeExact(vertex);
                    verts[vOut++] = pos.x() / 16.0F;
                    verts[vOut++] = pos.y() / 16.0F;
                    verts[vOut++] = pos.z() / 16.0F;
                    verts[vOut++] = (float) GET_VERT_U.invokeExact(vertex);
                    verts[vOut++] = (float) GET_VERT_V.invokeExact(vertex);
                }
                float vx0 = verts[qBase], vy0 = verts[qBase + 1], vz0 = verts[qBase + 2];
                float u0 = verts[qBase + 3], vt0 = verts[qBase + 4];
                float vx1 = verts[qBase + 5], vy1 = verts[qBase + 6], vz1 = verts[qBase + 7];
                float u1 = verts[qBase + 8], vt1 = verts[qBase + 9];
                float vx2 = verts[qBase + 10], vy2 = verts[qBase + 11], vz2 = verts[qBase + 12];
                float u2 = verts[qBase + 13], vt2 = verts[qBase + 14];
                float u3 = verts[qBase + 18], vt3 = verts[qBase + 19];

                float dx1 = vx1 - vx0, dy1 = vy1 - vy0, dz1 = vz1 - vz0;
                float dx2 = vx2 - vx0, dy2 = vy2 - vy0, dz2 = vz2 - vz0;
                float du1 = u1 - u0, dv1 = vt1 - vt0;
                float du2 = u2 - u0, dv2 = vt2 - vt0;
                float det = du1 * dv2 - du2 * dv1;
                float r = ((double) det == 0.0D) ? 1.0F : 1.0F / det;
                float tx = r * (dv2 * dx1 - dv1 * dx2);
                float ty = r * (dv2 * dy1 - dv1 * dy2);
                float tz = r * (dv2 * dz1 - dv1 * dz2);
                float lenSq = tx * tx + ty * ty + tz * tz;
                float invLen = (lenSq == 0.0F) ? 1.0F : (float) (1.0D / Math.sqrt(lenSq));
                tx *= invLen;
                ty *= invLen;
                tz *= invLen;
                float bx = r * (-du2 * dx1 + du1 * dx2);
                float by = r * (-du2 * dy1 + du1 * dy2);
                float bz = r * (-du2 * dz1 + du1 * dz2);
                float cx = ty * onz - tz * ony;
                float cy = tz * onx - tx * onz;
                float cz = tx * ony - ty * onx;
                float dot = bx * cx + by * cy + bz * cz;

                irisMeta[mOut++] = (u0 + u1 + u2 + u3) * 0.25F;
                irisMeta[mOut++] = (vt0 + vt1 + vt2 + vt3) * 0.25F;
                irisMeta[mOut++] = tx;
                irisMeta[mOut++] = ty;
                irisMeta[mOut++] = tz;
                irisMeta[mOut++] = dot < 0.0F ? -1.0F : 1.0F;
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        CompiledCube compiled = new CompiledCube(verts, norms, irisMeta, polyCount, true);
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

    private static final class IrisEntityFormatHolder {
        static final boolean AVAILABLE;
        static final Object IRIS_ENTITY_VF;
        static final VertexFormatDescription IRIS_ENTITY_DESC;
        static final MethodHandle BB_GET_FORMAT;
        static final Class<?> SBB_CLASS;
        static final MethodHandle SBB_GET_ORIG;
        static final Object CAPTURED_STATE;
        static final MethodHandle GET_ENTITY_ID;
        static final MethodHandle GET_BE_ID;
        static final MethodHandle GET_ITEM_ID;
        static final Object IRIS_API_INSTANCE;
        static final MethodHandle IS_SHADER_IN_USE;
        static final MethodHandle GET_EXTENDED_VF;

        static {
            boolean ok = false;
            Object vf = null;
            VertexFormatDescription desc = null;
            MethodHandle bbFmt = null;
            Class<?> sbbCls = null;
            MethodHandle sbbOrig = null;
            Object capState = null;
            MethodHandle getEnt = null;
            MethodHandle getBe = null;
            MethodHandle getItem = null;
            Object irisApi = null;
            MethodHandle isShader = null;
            MethodHandle getExtVf = null;
            try {
                ClassLoader cl = EmfRenderPerf.class.getClassLoader();
                Class<?> irisVfCls = Class.forName("net.irisshaders.iris.vertices.IrisVertexFormats", false, cl);
                vf = irisVfCls.getField("ENTITY").get(null);
                if (vf instanceof com.mojang.blaze3d.vertex.VertexFormat entityVf && entityVf.m_86020_() == 56) {
                    desc = net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatRegistry.instance().get(entityVf);
                    Field fmtField = com.mojang.blaze3d.vertex.BufferBuilder.class.getDeclaredField("f_85658_");
                    fmtField.setAccessible(true);
                    bbFmt = MethodHandles.lookup().unreflectGetter(fmtField)
                            .asType(MethodType.methodType(Object.class, Object.class));
                    try {
                        sbbCls = Class.forName(
                                "me.jellysquid.mods.sodium.client.render.vertex.buffer.SodiumBufferBuilder", false, cl);
                        java.lang.reflect.Method origM = sbbCls.getMethod("getOriginalBufferBuilder");
                        sbbOrig = MethodHandles.lookup().unreflect(origM)
                                .asType(MethodType.methodType(Object.class, Object.class));
                    } catch (Throwable ignored) {
                        sbbCls = null;
                        sbbOrig = null;
                    }
                    Class<?> capCls = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState", false, cl);
                    capState = capCls.getField("INSTANCE").get(null);
                    MethodHandles.Lookup pub = MethodHandles.publicLookup();
                    getEnt = pub.findVirtual(capCls, "getCurrentRenderedEntity", MethodType.methodType(int.class))
                            .asType(MethodType.methodType(int.class, Object.class));
                    getBe = pub.findVirtual(capCls, "getCurrentRenderedBlockEntity", MethodType.methodType(int.class))
                            .asType(MethodType.methodType(int.class, Object.class));
                    getItem = pub.findVirtual(capCls, "getCurrentRenderedItem", MethodType.methodType(int.class))
                            .asType(MethodType.methodType(int.class, Object.class));
                    try {
                        Class<?> apiCls = Class.forName("net.irisshaders.iris.api.v0.IrisApi", false, cl);
                        irisApi = apiCls.getMethod("getInstance").invoke(null);
                        isShader = pub.findVirtual(apiCls, "isShaderPackInUse", MethodType.methodType(boolean.class))
                                .asType(MethodType.methodType(boolean.class, Object.class));
                        Class<?> immCls = Class.forName("net.irisshaders.iris.vertices.ImmediateState", false, cl);
                        Field extField = immCls.getField("renderWithExtendedVertexFormat");
                        getExtVf = MethodHandles.lookup().unreflectGetter(extField)
                                .asType(MethodType.methodType(boolean.class));
                    } catch (Throwable ignored) {
                        irisApi = null;
                        isShader = null;
                        getExtVf = null;
                    }
                    ok = desc != null && capState != null;
                }
            } catch (Throwable ignored) {
                ok = false;
            }
            AVAILABLE = ok;
            IRIS_ENTITY_VF = vf;
            IRIS_ENTITY_DESC = desc;
            BB_GET_FORMAT = bbFmt;
            SBB_CLASS = sbbCls;
            SBB_GET_ORIG = sbbOrig;
            CAPTURED_STATE = capState;
            GET_ENTITY_ID = getEnt;
            GET_BE_ID = getBe;
            GET_ITEM_ID = getItem;
            IRIS_API_INSTANCE = irisApi;
            IS_SHADER_IN_USE = isShader;
            GET_EXTENDED_VF = getExtVf;
        }
    }

    private static final class SodiumFastPath {
        private static final int MODEL_VERTEX_STRIDE = 36;
        private static final int IRIS_ENTITY_STRIDE = 56;
        private static final long SCRATCH_PTR;
        private static volatile Thread renderThread;

        static {
            long ptr = 0L;
            try {
                ptr = org.lwjgl.system.MemoryUtil.nmemAlignedAlloc(
                        64L, (long) (SODIUM_BATCH_QUADS << 2) * IRIS_ENTITY_STRIDE);
            } catch (Throwable ignored) {
                ptr = 0L;
            }
            SCRATCH_PTR = ptr;
        }

        private SodiumFastPath() {
        }

        private static void pushScratch(
                VertexBufferWriter writer, long ptr, int vertexCount, VertexFormatDescription format) {
            // Composite writers can allocate a copy of the whole batch on the supplied stack.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                writer.push(stack, ptr, vertexCount, format);
            }
        }

        private static boolean isRenderThread() {
            Thread cur = Thread.currentThread();
            Thread rt = renderThread;
            if (rt != null) {
                return cur == rt;
            }
            if ("Render thread".equals(cur.getName())) {
                renderThread = cur;
                return true;
            }
            return false;
        }

        private static boolean isIrisEntityBuffer(VertexConsumer consumer) {
            if (!IrisEntityFormatHolder.AVAILABLE) {
                return false;
            }
            try {
                if (consumer instanceof com.mojang.blaze3d.vertex.BufferBuilder) {
                    return IrisEntityFormatHolder.BB_GET_FORMAT.invokeExact((Object) consumer)
                            == IrisEntityFormatHolder.IRIS_ENTITY_VF;
                }
                if (IrisEntityFormatHolder.SBB_CLASS != null
                        && IrisEntityFormatHolder.SBB_CLASS.isInstance(consumer)) {
                    Object orig = IrisEntityFormatHolder.SBB_GET_ORIG.invokeExact((Object) consumer);
                    return orig != null
                            && IrisEntityFormatHolder.BB_GET_FORMAT.invokeExact(orig)
                            == IrisEntityFormatHolder.IRIS_ENTITY_VF;
                }
            } catch (Throwable ignored) {
            }
            return false;
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
            VertexBufferWriter writer = VertexBufferWriter.tryOf(consumer);
            if (writer == null) {
                return false;
            }
            if (isIrisEntityBuffer(consumer)) {
                emitIrisEntityQuads(cube, poseMat, normMat, writer, packedLight, packedOverlay, red, green, blue, alpha);
                return true;
            }
            VertexFormatDescription fmt = ModelVertexFormatHolder.FORMAT;
            if (fmt == null) {
                return false;
            }
            emitQuads(cube, poseMat, normMat, writer, fmt, packedLight, packedOverlay, red, green, blue, alpha);
            return true;
        }

        private static int packNormI8(float x, float y, float z, float w) {
            int bx = (int) (Math.max(-1.0F, Math.min(1.0F, x)) * 127.0F) & 0xFF;
            int by = (int) (Math.max(-1.0F, Math.min(1.0F, y)) * 127.0F) & 0xFF;
            int bz = (int) (Math.max(-1.0F, Math.min(1.0F, z)) * 127.0F) & 0xFF;
            int bw = (int) (Math.max(-1.0F, Math.min(1.0F, w)) * 127.0F) & 0xFF;
            return bx | (by << 8) | (bz << 16) | (bw << 24);
        }

        private static void fillIrisBatch(
                long ptr,
                int qStart,
                int batchQuads,
                float[] verts,
                float[] norms,
                float[] irisMeta,
                float m00, float m01, float m02,
                float m10, float m11, float m12,
                float m20, float m21, float m22,
                float m30, float m31, float m32,
                float n00, float n01, float n02,
                float n10, float n11, float n12,
                float n20, float n21, float n22,
                float detFlip,
                int colorAbgr,
                int packedOverlay,
                int packedLight,
                short entityId,
                short beId,
                short itemId) {
            long cur = ptr;
            int vIdx = qStart * 20;
            int nIdx = qStart * 3;
            int mIdx = qStart * 6;
            for (int q = 0; q < batchQuads; q++) {
                float onx = norms[nIdx];
                float ony = norms[nIdx + 1];
                float onz = norms[nIdx + 2];
                nIdx += 3;

                float nx = org.joml.Math.fma(n00, onx, org.joml.Math.fma(n10, ony, n20 * onz));
                float ny = org.joml.Math.fma(n01, onx, org.joml.Math.fma(n11, ony, n21 * onz));
                float nz = org.joml.Math.fma(n02, onx, org.joml.Math.fma(n12, ony, n22 * onz));
                int normPacked = NormI8.pack(nx, ny, nz);

                float midU = irisMeta[mIdx];
                float midV = irisMeta[mIdx + 1];
                float otx = irisMeta[mIdx + 2];
                float oty = irisMeta[mIdx + 3];
                float otz = irisMeta[mIdx + 4];
                float oh = irisMeta[mIdx + 5];
                mIdx += 6;

                float tx = org.joml.Math.fma(m00, otx, org.joml.Math.fma(m10, oty, m20 * otz));
                float ty = org.joml.Math.fma(m01, otx, org.joml.Math.fma(m11, oty, m21 * otz));
                float tz = org.joml.Math.fma(m02, otx, org.joml.Math.fma(m12, oty, m22 * otz));
                float tLenSq = org.joml.Math.fma(tx, tx, org.joml.Math.fma(ty, ty, tz * tz));
                if (tLenSq > 0.0F) {
                    float invLen = org.joml.Math.invsqrt(tLenSq);
                    tx *= invLen;
                    ty *= invLen;
                    tz *= invLen;
                }
                int tangent = packNormI8(tx, ty, tz, oh * detFlip);

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
                    org.lwjgl.system.MemoryUtil.memPutShort(cur + 36L, entityId);
                    org.lwjgl.system.MemoryUtil.memPutShort(cur + 38L, beId);
                    org.lwjgl.system.MemoryUtil.memPutShort(cur + 40L, itemId);
                    org.lwjgl.system.MemoryUtil.memPutFloat(cur + 42L, midU);
                    org.lwjgl.system.MemoryUtil.memPutFloat(cur + 46L, midV);
                    org.lwjgl.system.MemoryUtil.memPutInt(cur + 50L, tangent);
                    cur += IRIS_ENTITY_STRIDE;
                }
            }
        }

        static void emitIrisEntityQuads(
                CompiledCube cube,
                Matrix4f poseMat,
                Matrix3f normMat,
                VertexBufferWriter writer,
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
            float[] irisMeta = cube.quadIrisMeta;

            short entityId = 0;
            short beId = 0;
            short itemId = 0;
            try {
                Object state = IrisEntityFormatHolder.CAPTURED_STATE;
                entityId = (short) (int) IrisEntityFormatHolder.GET_ENTITY_ID.invokeExact(state);
                beId = (short) (int) IrisEntityFormatHolder.GET_BE_ID.invokeExact(state);
                itemId = (short) (int) IrisEntityFormatHolder.GET_ITEM_ID.invokeExact(state);
            } catch (Throwable ignored) {
            }

            float m00 = poseMat.m00(), m01 = poseMat.m01(), m02 = poseMat.m02();
            float m10 = poseMat.m10(), m11 = poseMat.m11(), m12 = poseMat.m12();
            float m20 = poseMat.m20(), m21 = poseMat.m21(), m22 = poseMat.m22();
            float m30 = poseMat.m30(), m31 = poseMat.m31(), m32 = poseMat.m32();

            float n00 = normMat.m00(), n01 = normMat.m01(), n02 = normMat.m02();
            float n10 = normMat.m10(), n11 = normMat.m11(), n12 = normMat.m12();
            float n20 = normMat.m20(), n21 = normMat.m21(), n22 = normMat.m22();

            float det3 = m00 * (m11 * m22 - m21 * m12)
                    - m10 * (m01 * m22 - m21 * m02)
                    + m20 * (m01 * m12 - m11 * m02);
            float detFlip = det3 < 0.0F ? -1.0F : 1.0F;

            VertexFormatDescription irisFmt = IrisEntityFormatHolder.IRIS_ENTITY_DESC;
            boolean useScratch = SCRATCH_PTR != 0L && isRenderThread();
            for (int qStart = 0; qStart < quadCount; qStart += SODIUM_BATCH_QUADS) {
                int batchQuads = Math.min(SODIUM_BATCH_QUADS, quadCount - qStart);
                int vertexCount = batchQuads << 2;
                if (useScratch) {
                    fillIrisBatch(
                            SCRATCH_PTR, qStart, batchQuads, verts, norms, irisMeta,
                            m00, m01, m02, m10, m11, m12, m20, m21, m22, m30, m31, m32,
                            n00, n01, n02, n10, n11, n12, n20, n21, n22,
                            detFlip, colorAbgr, packedOverlay, packedLight, entityId, beId, itemId);
                    pushScratch(writer, SCRATCH_PTR, vertexCount, irisFmt);
                } else {
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        long ptr = stack.nmalloc(vertexCount * IRIS_ENTITY_STRIDE);
                        fillIrisBatch(
                                ptr, qStart, batchQuads, verts, norms, irisMeta,
                                m00, m01, m02, m10, m11, m12, m20, m21, m22, m30, m31, m32,
                                n00, n01, n02, n10, n11, n12, n20, n21, n22,
                                detFlip, colorAbgr, packedOverlay, packedLight, entityId, beId, itemId);
                        writer.push(stack, ptr, vertexCount, irisFmt);
                    }
                }
            }
        }

        private static void fillModelBatch(
                long ptr,
                int qStart,
                int batchQuads,
                float[] verts,
                float[] norms,
                float m00, float m01, float m02,
                float m10, float m11, float m12,
                float m20, float m21, float m22,
                float m30, float m31, float m32,
                float n00, float n01, float n02,
                float n10, float n11, float n12,
                float n20, float n21, float n22,
                int colorAbgr,
                int packedOverlay,
                int packedLight) {
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

            boolean useScratch = SCRATCH_PTR != 0L && isRenderThread();
            for (int qStart = 0; qStart < quadCount; qStart += SODIUM_BATCH_QUADS) {
                int batchQuads = Math.min(SODIUM_BATCH_QUADS, quadCount - qStart);
                int vertexCount = batchQuads << 2;
                if (useScratch) {
                    fillModelBatch(
                            SCRATCH_PTR, qStart, batchQuads, verts, norms,
                            m00, m01, m02, m10, m11, m12, m20, m21, m22, m30, m31, m32,
                            n00, n01, n02, n10, n11, n12, n20, n21, n22,
                            colorAbgr, packedOverlay, packedLight);
                    pushScratch(writer, SCRATCH_PTR, vertexCount, format);
                } else {
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        long ptr = stack.nmalloc(vertexCount * MODEL_VERTEX_STRIDE);
                        fillModelBatch(
                                ptr, qStart, batchQuads, verts, norms,
                                m00, m01, m02, m10, m11, m12, m20, m21, m22, m30, m31, m32,
                                n00, n01, n02, n10, n11, n12, n20, n21, n22,
                                colorAbgr, packedOverlay, packedLight);
                        writer.push(stack, ptr, vertexCount, format);
                    }
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

    public static void disablePoseCaching(PoseStack poseStack) {
        if (poseStack instanceof CachingPoseStack caching) {
            caching.embeddium$setCachingEnabled(false);
        }
    }

    /**
     * Direct bridge interface implemented on vanilla {@code Frustum} and Iris
     * {@code AdvancedShadowCullingFrustum} via boot-agent bytecode so
     * {@link #tryFastEntityFrustumCheck} avoids {@code MethodHandle.invokeExact} overhead.
     */
    public interface FastBoxFrustum {
        boolean sunlit$isBoxVisible(
                double minX, double minY, double minZ,
                double maxX, double maxY, double maxZ);
    }

    private static final class FrustumHandlesHolder {
        static final MethodHandle VANILLA_CUBE_IN_FRUSTUM;
        static final Class<?> IRIS_SHADOW_FRUSTUM_CLASS;
        static final MethodHandle IRIS_SHADOW_BOX_VISIBLE;

        static {
            MethodHandle vanillaMh = null;
            Class<?> irisCls = null;
            MethodHandle irisMh = null;
            try {
                java.lang.reflect.Method m = net.minecraft.client.renderer.culling.Frustum.class.getDeclaredMethod(
                        "m_113006_", double.class, double.class, double.class, double.class, double.class, double.class);
                m.setAccessible(true);
                vanillaMh = MethodHandles.lookup().unreflect(m).asType(MethodType.methodType(
                        boolean.class, Object.class,
                        double.class, double.class, double.class, double.class, double.class, double.class));
            } catch (Throwable ignored) {
                vanillaMh = null;
            }
            try {
                Class<?> cls = Class.forName(
                        "net.irisshaders.iris.shadows.frustum.advanced.AdvancedShadowCullingFrustum",
                        false, EmfRenderPerf.class.getClassLoader());
                java.lang.reflect.Method m = cls.getDeclaredMethod(
                        "sunlit$isBoxVisible",
                        double.class, double.class, double.class, double.class, double.class, double.class);
                m.setAccessible(true);
                irisMh = MethodHandles.lookup().unreflect(m).asType(MethodType.methodType(
                        boolean.class, Object.class,
                        double.class, double.class, double.class, double.class, double.class, double.class));
                irisCls = cls;
            } catch (Throwable ignored) {
                irisCls = null;
                irisMh = null;
            }
            VANILLA_CUBE_IN_FRUSTUM = vanillaMh;
            IRIS_SHADOW_FRUSTUM_CLASS = irisCls;
            IRIS_SHADOW_BOX_VISIBLE = irisMh;
        }
    }

    /**
     * Allocation-free equivalent of {@code EntityRenderer.shouldRender}'s
     * {@code frustum.isVisible(entity.getBoundingBoxForCulling().inflate(0.5D))} check.
     * Returns {@code 1} if visible, {@code 0} if culled, or {@code -1} to fall back to vanilla.
     */
    public static int tryFastEntityFrustumCheck(
            net.minecraft.world.entity.Entity entity,
            net.minecraft.client.renderer.culling.Frustum frustum) {
        if (entity == null || frustum == null) {
            return -1;
        }
        Class<?> fCls = frustum.getClass();
        boolean isVanilla = fCls == net.minecraft.client.renderer.culling.Frustum.class;
        if (!isVanilla && fCls != FrustumHandlesHolder.IRIS_SHADOW_FRUSTUM_CLASS) {
            return -1;
        }
        boolean directFast = frustum instanceof FastBoxFrustum;
        if (!directFast) {
            if (isVanilla ? FrustumHandlesHolder.VANILLA_CUBE_IN_FRUSTUM == null
                    : FrustumHandlesHolder.IRIS_SHADOW_BOX_VISIBLE == null) {
                return -1;
            }
        }
        net.minecraft.world.phys.AABB raw = entity.m_6921_();
        double minX = raw.f_82288_ - 0.5D;
        double minY = raw.f_82289_ - 0.5D;
        double minZ = raw.f_82290_ - 0.5D;
        double maxX = raw.f_82291_ + 0.5D;
        double maxY = raw.f_82292_ + 0.5D;
        double maxZ = raw.f_82293_ + 0.5D;
        double size = ((maxX - minX) + (maxY - minY) + (maxZ - minZ)) / 3.0D;
        if (Double.isNaN(minX) || Double.isNaN(minY) || Double.isNaN(minZ)
                || Double.isNaN(maxX) || Double.isNaN(maxY) || Double.isNaN(maxZ)
                || size == 0.0D) {
            double ex = entity.m_20185_();
            double ey = entity.m_20186_();
            double ez = entity.m_20189_();
            minX = ex - 2.0D;
            minY = ey - 2.0D;
            minZ = ez - 2.0D;
            maxX = ex + 2.0D;
            maxY = ey + 2.0D;
            maxZ = ez + 2.0D;
        }
        if (directFast) {
            return ((FastBoxFrustum) frustum).sunlit$isBoxVisible(minX, minY, minZ, maxX, maxY, maxZ) ? 1 : 0;
        }
        try {
            boolean visible = isVanilla
                    ? (boolean) FrustumHandlesHolder.VANILLA_CUBE_IN_FRUSTUM.invokeExact(
                            (Object) frustum, minX, minY, minZ, maxX, maxY, maxZ)
                    : (boolean) FrustumHandlesHolder.IRIS_SHADOW_BOX_VISIBLE.invokeExact(
                            (Object) frustum, minX, minY, minZ, maxX, maxY, maxZ);
            return visible ? 1 : 0;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * Unboxed, empty-map fast-path variable lookup for EMF 3.2.4's
     * {@code EMFAnimationEntityContext.getEntityVariable(String, float)}, avoiding
     * {@code Float.valueOf} boxing and hash probing on empty or {@link FastEmfVarMap} maps.
     */
    public static float getEntityVariableFast(java.util.Map<String, Float> map, String key, float def) {
        if (map == null || map.isEmpty()) {
            return def;
        }
        if (map instanceof it.unimi.dsi.fastutil.objects.Object2FloatMap<?> o2f) {
            return o2f.getOrDefault(key, def);
        }
        Float v = map.get(key);
        return v != null ? v.floatValue() : def;
    }

    /**
     * Unboxed variable write for EMF 3.2.4's
     * {@code EMFAnimationEntityContext.setEntityVariable(String, float)}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void setEntityVariableFast(java.util.Map<String, Float> map, String key, float value) {
        if (map == null) {
            return;
        }
        if (map instanceof it.unimi.dsi.fastutil.objects.Object2FloatMap o2f) {
            o2f.put(key, value);
            return;
        }
        map.put(key, Float.valueOf(value));
    }

    /**
     * Compact, lazy-allocated {@link it.unimi.dsi.fastutil.objects.Object2FloatMap} for EMF
     * per-entity variable maps: allocates zero arrays for entities with no EMF variables, uses a
     * flat linear array for &lt;= 8 entries, and upgrades to an inline power-of-2 open-addressed
     * pointer-equality-first table for larger models (e.g. Fresh Animations' 20-40 variables).
     */
    public static final class FastEmfVarMap
            extends it.unimi.dsi.fastutil.objects.AbstractObject2FloatMap<String> {
        private static final int LINEAR_LIMIT = 8;
        private String[] keys;
        private float[] vals;
        private int size;
        private int mask; // 0 = linear mode; > 0 = open-addressed hash table of length (mask + 1)
        private boolean hasNullKey;
        private float nullVal;

        public FastEmfVarMap() {
        }

        public FastEmfVarMap(java.util.Map<? extends String, ? extends Float> src) {
            if (src == null || src.isEmpty()) {
                return;
            }
            if (src instanceof FastEmfVarMap other) {
                this.defRetValue = other.defRetValue;
                this.size = other.size;
                this.mask = other.mask;
                this.hasNullKey = other.hasNullKey;
                this.nullVal = other.nullVal;
                if (other.keys != null) {
                    this.keys = java.util.Arrays.copyOf(other.keys, other.keys.length);
                    this.vals = java.util.Arrays.copyOf(other.vals, other.vals.length);
                }
                return;
            }
            putAll(src);
        }

        @Override
        public int size() {
            return this.size;
        }

        @Override
        public boolean isEmpty() {
            return this.size == 0;
        }

        @Override
        public void clear() {
            if (this.size == 0) {
                return;
            }
            if (this.keys != null) {
                java.util.Arrays.fill(this.keys, null);
            }
            this.hasNullKey = false;
            this.size = 0;
        }

        private int findLinear(Object key) {
            int sz = this.size;
            String[] ks = this.keys;
            for (int i = 0; i < sz; i++) {
                if (ks[i] == key) {
                    return i;
                }
            }
            if (key != null) {
                for (int i = 0; i < sz; i++) {
                    if (key.equals(ks[i])) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private int findTable(Object key) {
            String[] ks = this.keys;
            int m = this.mask;
            int slot = key.hashCode() & m;
            String cur = ks[slot];
            if (cur == key) {
                return slot;
            }
            if (cur == null) {
                return -1;
            }
            if (key.equals(cur)) {
                return slot;
            }
            while (true) {
                slot = (slot + 1) & m;
                cur = ks[slot];
                if (cur == key) {
                    return slot;
                }
                if (cur == null) {
                    return -1;
                }
                if (key.equals(cur)) {
                    return slot;
                }
            }
        }

        @Override
        public float getFloat(Object key) {
            int sz = this.size;
            if (sz == 0) {
                return this.defRetValue;
            }
            if (this.mask == 0) {
                int idx = findLinear(key);
                return idx >= 0 ? this.vals[idx] : this.defRetValue;
            }
            if (key == null) {
                return this.hasNullKey ? this.nullVal : this.defRetValue;
            }
            int slot = findTable(key);
            return slot >= 0 ? this.vals[slot] : this.defRetValue;
        }

        @Override
        public float getOrDefault(Object key, float defaultValue) {
            int sz = this.size;
            if (sz == 0) {
                return defaultValue;
            }
            int m = this.mask;
            if (m == 0) {
                String[] ks = this.keys;
                for (int i = 0; i < sz; i++) {
                    if (ks[i] == key) {
                        return this.vals[i];
                    }
                }
                if (key != null) {
                    for (int i = 0; i < sz; i++) {
                        if (key.equals(ks[i])) {
                            return this.vals[i];
                        }
                    }
                }
                return defaultValue;
            }
            if (key == null) {
                return this.hasNullKey ? this.nullVal : defaultValue;
            }
            String[] ks = this.keys;
            int slot = key.hashCode() & m;
            String cur = ks[slot];
            if (cur == key) {
                return this.vals[slot];
            }
            if (cur == null) {
                return defaultValue;
            }
            if (key.equals(cur)) {
                return this.vals[slot];
            }
            while (true) {
                slot = (slot + 1) & m;
                cur = ks[slot];
                if (cur == key) {
                    return this.vals[slot];
                }
                if (cur == null) {
                    return defaultValue;
                }
                if (key.equals(cur)) {
                    return this.vals[slot];
                }
            }
        }

        @Override
        public boolean containsKey(Object key) {
            if (this.size == 0) {
                return false;
            }
            if (this.mask == 0) {
                return findLinear(key) >= 0;
            }
            return key == null ? this.hasNullKey : findTable(key) >= 0;
        }

        private void rehash(int newCap) {
            String[] oldKs = this.keys;
            float[] oldVs = this.vals;
            int oldMask = this.mask;
            String[] newKs = new String[newCap];
            float[] newVs = new float[newCap];
            int newMask = newCap - 1;
            if (oldMask == 0) {
                int sz = this.size;
                for (int i = 0; i < sz; i++) {
                    String k = oldKs[i];
                    if (k == null) {
                        this.hasNullKey = true;
                        this.nullVal = oldVs[i];
                    } else {
                        int s = k.hashCode() & newMask;
                        while (newKs[s] != null) {
                            s = (s + 1) & newMask;
                        }
                        newKs[s] = k;
                        newVs[s] = oldVs[i];
                    }
                }
            } else {
                for (int i = 0; i <= oldMask; i++) {
                    String k = oldKs[i];
                    if (k != null) {
                        int s = k.hashCode() & newMask;
                        while (newKs[s] != null) {
                            s = (s + 1) & newMask;
                        }
                        newKs[s] = k;
                        newVs[s] = oldVs[i];
                    }
                }
            }
            this.keys = newKs;
            this.vals = newVs;
            this.mask = newMask;
        }

        @Override
        public float put(String key, float value) {
            int m = this.mask;
            if (m == 0) {
                int sz = this.size;
                String[] ks = this.keys;
                if (sz > 0) {
                    for (int i = 0; i < sz; i++) {
                        if (ks[i] == key) {
                            float prev = this.vals[i];
                            this.vals[i] = value;
                            return prev;
                        }
                    }
                    if (key != null) {
                        for (int i = 0; i < sz; i++) {
                            if (key.equals(ks[i])) {
                                float prev = this.vals[i];
                                this.vals[i] = value;
                                return prev;
                            }
                        }
                    }
                }
                if (sz < LINEAR_LIMIT) {
                    if (ks == null) {
                        ks = new String[LINEAR_LIMIT];
                        this.keys = ks;
                        this.vals = new float[LINEAR_LIMIT];
                    }
                    ks[sz] = key;
                    this.vals[sz] = value;
                    this.size = sz + 1;
                    return this.defRetValue;
                }
                rehash(32);
                m = this.mask;
            }
            if (key == null) {
                if (this.hasNullKey) {
                    float prev = this.nullVal;
                    this.nullVal = value;
                    return prev;
                }
                this.hasNullKey = true;
                this.nullVal = value;
                this.size++;
                return this.defRetValue;
            }
            String[] ks = this.keys;
            int slot = key.hashCode() & m;
            String cur = ks[slot];
            while (cur != null) {
                if (cur == key || key.equals(cur)) {
                    float prev = this.vals[slot];
                    this.vals[slot] = value;
                    return prev;
                }
                slot = (slot + 1) & m;
                cur = ks[slot];
            }
            ks[slot] = key;
            this.vals[slot] = value;
            if (++this.size > ((m + 1) >> 1)) {
                rehash((m + 1) << 1);
            }
            return this.defRetValue;
        }

        @Override
        public float removeFloat(Object key) {
            if (this.size == 0) {
                return this.defRetValue;
            }
            int m = this.mask;
            if (m == 0) {
                int idx = findLinear(key);
                if (idx < 0) {
                    return this.defRetValue;
                }
                float prev = this.vals[idx];
                int last = --this.size;
                if (idx != last) {
                    this.keys[idx] = this.keys[last];
                    this.vals[idx] = this.vals[last];
                }
                this.keys[last] = null;
                return prev;
            }
            if (key == null) {
                if (!this.hasNullKey) {
                    return this.defRetValue;
                }
                this.hasNullKey = false;
                this.size--;
                return this.nullVal;
            }
            int pos = findTable(key);
            if (pos < 0) {
                return this.defRetValue;
            }
            float prev = this.vals[pos];
            this.size--;
            String[] ks = this.keys;
            float[] vs = this.vals;
            int last;
            while (true) {
                last = pos;
                pos = (pos + 1) & m;
                while (true) {
                    String cur = ks[pos];
                    if (cur == null) {
                        ks[last] = null;
                        return prev;
                    }
                    int slot = cur.hashCode() & m;
                    if (last <= pos ? (last >= slot || slot > pos) : (last >= slot && slot > pos)) {
                        break;
                    }
                    pos = (pos + 1) & m;
                }
                ks[last] = ks[pos];
                vs[last] = vs[pos];
            }
        }

        @Override
        public it.unimi.dsi.fastutil.objects.ObjectSet<Entry<String>> object2FloatEntrySet() {
            it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<Entry<String>> set =
                    new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>(this.size);
            if (this.size == 0) {
                return set;
            }
            if (this.mask == 0) {
                for (int i = 0; i < this.size; i++) {
                    set.add(new BasicEntry<>(this.keys[i], this.vals[i]));
                }
            } else {
                if (this.hasNullKey) {
                    set.add(new BasicEntry<>(null, this.nullVal));
                }
                String[] ks = this.keys;
                float[] vs = this.vals;
                for (int i = 0; i < ks.length; i++) {
                    if (ks[i] != null) {
                        set.add(new BasicEntry<>(ks[i], vs[i]));
                    }
                }
            }
            return set;
        }
    }

    private static final class RadixSortScratch {
        int[] keys = new int[0];
        int[] scratch = new int[0];
        final int[] histogram = new int[2048 + 2048 + 1024];

        void ensureCapacity(int len) {
            if (this.keys.length < len) {
                int cap = Math.max(len, Math.max(512, this.keys.length << 1));
                this.keys = new int[cap];
                this.scratch = new int[cap];
            }
        }
    }

    private static final RadixSortScratch RENDER_RADIX_SCRATCH = new RadixSortScratch();

    /**
     * Pooled 3-pass (11+11+10-bit) descending radix sort matching Emberbium
     * {@code RadixSort.radixSortDescending(float[])} bit-for-bit, reusing internal L1-resident
     * scratch arrays on the render thread and skipping the upfront identity-index fill on the
     * first active pass.
     */
    public static int[] radixSortDescending(float[] floatKeys) {
        int len = floatKeys.length;
        if (len == 0) {
            return new int[0];
        }
        if (len == 1) {
            return new int[] { 0 };
        }
        RadixSortScratch buf = SodiumFastPath.isRenderThread() ? RENDER_RADIX_SCRATCH : new RadixSortScratch();
        buf.ensureCapacity(len);
        int[] encoded = buf.keys;
        int[] histogram = buf.histogram;
        java.util.Arrays.fill(histogram, 0);

        for (int i = 0; i < len; i++) {
            int f = Float.floatToRawIntBits(floatKeys[i]);
            int k = (f ^ ((f >> 31) | 0x80000000)) ^ -1;
            encoded[i] = k;
            histogram[k & 0x7FF]++;
            histogram[2048 + ((k >>> 11) & 0x7FF)]++;
            histogram[4096 + (k >>> 22)]++;
        }

        int k0 = encoded[0];
        boolean skip0 = histogram[k0 & 0x7FF] == len;
        boolean skip1 = histogram[2048 + ((k0 >>> 11) & 0x7FF)] == len;
        boolean skip2 = histogram[4096 + (k0 >>> 22)] == len;

        int[] out = new int[len];
        if (skip0 && skip1 && skip2) {
            for (int i = 0; i < len; i++) {
                out[i] = i;
            }
            return out;
        }

        int activePasses = (skip0 ? 0 : 1) + (skip1 ? 0 : 1) + (skip2 ? 0 : 1);
        // Pick initial destination so the final pass lands directly in `out` with zero arraycopy!
        int[] dst = (activePasses & 1) == 1 ? out : buf.scratch;
        int[] src = (activePasses & 1) == 1 ? buf.scratch : out;
        boolean firstPass = true;

        if (!skip0) {
            int sum = 0;
            for (int b = 0; b < 2048; b++) {
                int c = histogram[b];
                histogram[b] = sum;
                sum += c;
            }
            for (int i = 0; i < len; i++) {
                dst[histogram[encoded[i] & 0x7FF]++] = i;
            }
            firstPass = false;
            int[] tmp = src;
            src = dst;
            dst = tmp;
        }

        if (!skip1) {
            int sum = 0;
            for (int b = 2048; b < 4096; b++) {
                int c = histogram[b];
                histogram[b] = sum;
                sum += c;
            }
            if (firstPass) {
                for (int i = 0; i < len; i++) {
                    dst[histogram[2048 + ((encoded[i] >>> 11) & 0x7FF)]++] = i;
                }
                firstPass = false;
            } else {
                for (int i = 0; i < len; i++) {
                    int idx = src[i];
                    dst[histogram[2048 + ((encoded[idx] >>> 11) & 0x7FF)]++] = idx;
                }
            }
            int[] tmp = src;
            src = dst;
            dst = tmp;
        }

        if (!skip2) {
            int sum = 0;
            for (int b = 4096; b < 5120; b++) {
                int c = histogram[b];
                histogram[b] = sum;
                sum += c;
            }
            if (firstPass) {
                for (int i = 0; i < len; i++) {
                    dst[histogram[4096 + (encoded[i] >>> 22)]++] = i;
                }
            } else {
                for (int i = 0; i < len; i++) {
                    int idx = src[i];
                    dst[histogram[4096 + (encoded[idx] >>> 22)]++] = idx;
                }
            }
        }

        return out;
    }

    private static final class VanillaAirHolder {
        static final net.minecraft.world.level.block.state.BlockState AIR =
                net.minecraft.world.level.block.Blocks.f_50016_.m_49966_();
        static final net.minecraft.world.level.block.state.BlockState CAVE_AIR =
                net.minecraft.world.level.block.Blocks.f_50628_.m_49966_();
        static final net.minecraft.world.level.block.state.BlockState VOID_AIR =
                net.minecraft.world.level.block.Blocks.f_50627_.m_49966_();
    }

    /**
     * Ultra-fast reference check for vanilla AIR / CAVE_AIR / VOID_AIR block states so chunk
     * meshing can skip per-air-block MixinExtras/OBE/Iris hooks.
     */
    public static boolean isVanillaAir(net.minecraft.world.level.block.state.BlockState state) {
        return state == VanillaAirHolder.AIR
                || state == VanillaAirHolder.CAVE_AIR
                || state == VanillaAirHolder.VOID_AIR;
    }

    private static final class IrisBlockRenderTypeCache {
        final java.util.Map<?, ?> sourceMap;
        final java.util.IdentityHashMap<net.minecraft.world.level.block.Block, Object> byBlock;

        IrisBlockRenderTypeCache(
                java.util.Map<?, ?> sourceMap,
                java.util.IdentityHashMap<net.minecraft.world.level.block.Block, Object> byBlock) {
            this.sourceMap = sourceMap;
            this.byBlock = byBlock;
        }
    }

    private static volatile IrisBlockRenderTypeCache irisBlockRenderTypeCache;

    /**
     * Identity-cached {@code Block -> ChunkRenderTypeSet} lookup replacing per-block
     * {@code ForgeRegistries.BLOCKS.getDelegateOrThrow(block)} + {@code Map.get(holder)} in
     * Iris's {@code oculus$overrideRenderTypes} and {@code iris$setCustomRenderType}.
     */
    public static Object getIrisBlockRenderTypeOverride(
            java.util.Map<?, ?> blockTypeIds,
            net.minecraft.world.level.block.Block block) {
        if (blockTypeIds == null || block == null) {
            return null;
        }
        IrisBlockRenderTypeCache cache = irisBlockRenderTypeCache;
        if (cache == null || cache.sourceMap != blockTypeIds) {
            cache = buildIrisBlockRenderTypeCache(blockTypeIds);
            irisBlockRenderTypeCache = cache;
        }
        java.util.IdentityHashMap<net.minecraft.world.level.block.Block, Object> byBlock = cache.byBlock;
        return byBlock == null ? null : byBlock.get(block);
    }

    private static IrisBlockRenderTypeCache buildIrisBlockRenderTypeCache(java.util.Map<?, ?> sourceMap) {
        if (sourceMap.isEmpty()) {
            return new IrisBlockRenderTypeCache(sourceMap, null);
        }
        java.util.IdentityHashMap<net.minecraft.world.level.block.Block, Object> map =
                new java.util.IdentityHashMap<>(sourceMap.size() * 2);
        for (java.util.Map.Entry<?, ?> entry : sourceMap.entrySet()) {
            Object key = entry.getKey();
            Object val = entry.getValue();
            if (val == null) {
                continue;
            }
            if (key instanceof net.minecraft.core.Holder<?> holder && holder.m_203633_()) {
                Object inner = holder.m_203334_();
                if (inner instanceof net.minecraft.world.level.block.Block b) {
                    map.put(b, val);
                }
            } else if (key instanceof net.minecraft.world.level.block.Block b) {
                map.put(b, val);
            }
        }
        return new IrisBlockRenderTypeCache(sourceMap, map.isEmpty() ? null : map);
    }

    private static int multARGBIntsFast(int a, int b) {
        if (a == -1) {
            return b;
        }
        if (b == -1) {
            return a;
        }
        int c0 = (((a >>> 24) * (b >>> 24)) / 255) << 24;
        int c1 = ((((a >>> 16) & 0xFF) * ((b >>> 16) & 0xFF)) / 255) << 16;
        int c2 = ((((a >>> 8) & 0xFF) * ((b >>> 8) & 0xFF)) / 255) << 8;
        int c3 = ((a & 0xFF) * (b & 0xFF)) / 255;
        return c0 | c1 | c2 | c3;
    }

    private static int computeQuadTangentPacked(
            float nx, float ny, float nz,
            float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2) {
        float dx1 = x1 - x0, dy1 = y1 - y0, dz1 = z1 - z0;
        float dx2 = x2 - x0, dy2 = y2 - y0, dz2 = z2 - z0;
        float du1 = u1 - u0, dv1 = v1 - v0;
        float du2 = u2 - u0, dv2 = v2 - v0;
        float det = du1 * dv2 - du2 * dv1;
        float r = ((double) det == 0.0D) ? 1.0F : 1.0F / det;
        float tx = r * (dv2 * dx1 - dv1 * dx2);
        float ty = r * (dv2 * dy1 - dv1 * dy2);
        float tz = r * (dv2 * dz1 - dv1 * dz2);
        float lenSq = org.joml.Math.fma(tx, tx, org.joml.Math.fma(ty, ty, tz * tz));
        if (lenSq > 0.0F) {
            float invLen = org.joml.Math.invsqrt(lenSq);
            tx *= invLen;
            ty *= invLen;
            tz *= invLen;
        }
        float bx = r * (-du2 * dx1 + du1 * dx2);
        float by = r * (-du2 * dy1 + du1 * dy2);
        float bz = r * (-du2 * dz1 + du1 * dz2);
        float cx = ty * nz - tz * ny;
        float cy = tz * nx - tx * nz;
        float cz = tx * ny - ty * nx;
        float dot = bx * cx + by * cy + bz * cz;
        return SodiumFastPath.packNormI8(tx, ty, tz, dot < 0.0F ? -1.0F : 1.0F);
    }

    /**
     * Bridge interface implemented on vanilla {@code BakedQuad} via boot-agent bytecode to cache
     * static object-space Iris metadata (midUV, object-space tangent & handedness, and uniform
     * face normal) once per immutable quad.
     */
    public interface BakedQuadIrisMeta {
        float[] sunlit$getIrisMeta();
        void sunlit$setIrisMeta(float[] meta);
    }

    private static float[] computeBakedQuadIrisMeta(me.jellysquid.mods.sodium.client.model.quad.ModelQuadView quad) {
        float u0 = quad.getTexU(0), u1 = quad.getTexU(1), u2 = quad.getTexU(2), u3 = quad.getTexU(3);
        float v0 = quad.getTexV(0), v1 = quad.getTexV(1), v2 = quad.getTexV(2), v3 = quad.getTexV(3);
        float midU = (u0 + u1 + u2 + u3) * 0.25F;
        float midV = (v0 + v1 + v2 + v3) * 0.25F;

        float x0 = quad.getX(0), y0 = quad.getY(0), z0 = quad.getZ(0);
        float x1 = quad.getX(1), y1 = quad.getY(1), z1 = quad.getZ(1);
        float x2 = quad.getX(2), y2 = quad.getY(2), z2 = quad.getZ(2);

        int fn0 = quad.getForgeNormal(0);
        int fn1 = quad.getForgeNormal(1);
        int fn2 = quad.getForgeNormal(2);
        int fn3 = quad.getForgeNormal(3);
        boolean uniformFaceNormal = ((fn0 | fn1 | fn2 | fn3) & 0xFFFFFF) == 0;

        float onx, ony, onz;
        if ((fn3 & 0xFFFFFF) == 0) {
            net.minecraft.core.Direction d = quad.getLightFace();
            onx = d.m_122429_();
            ony = d.m_122430_();
            onz = d.m_122431_();
        } else {
            onx = NormI8.unpackX(fn3);
            ony = NormI8.unpackY(fn3);
            onz = NormI8.unpackZ(fn3);
        }

        float dx1 = x1 - x0, dy1 = y1 - y0, dz1 = z1 - z0;
        float dx2 = x2 - x0, dy2 = y2 - y0, dz2 = z2 - z0;
        float du1 = u1 - u0, dv1 = v1 - v0;
        float du2 = u2 - u0, dv2 = v2 - v0;
        float det = du1 * dv2 - du2 * dv1;
        float r = ((double) det == 0.0D) ? 1.0F : 1.0F / det;
        float tx = r * (dv2 * dx1 - dv1 * dx2);
        float ty = r * (dv2 * dy1 - dv1 * dy2);
        float tz = r * (dv2 * dz1 - dv1 * dz2);
        float lenSq = tx * tx + ty * ty + tz * tz;
        float invLen = (lenSq == 0.0F) ? 1.0F : (float) (1.0D / Math.sqrt(lenSq));
        tx *= invLen;
        ty *= invLen;
        tz *= invLen;
        float bx = r * (-du2 * dx1 + du1 * dx2);
        float by = r * (-du2 * dy1 + du1 * dy2);
        float bz = r * (-du2 * dz1 + du1 * dz2);
        float cx = ty * onz - tz * ony;
        float cy = tz * onx - tx * onz;
        float cz = tx * ony - ty * onx;
        float dot = bx * cx + by * cy + bz * cz;
        float oh = dot < 0.0F ? -1.0F : 1.0F;

        if (uniformFaceNormal) {
            return new float[] { midU, midV, tx, ty, tz, oh, onx, ony, onz };
        }
        return new float[] { midU, midV, tx, ty, tz, oh };
    }

    /**
     * Zero-allocation replacement for Iris {@code EntityVertex.writeQuadVertices}, eliminating
     * per-quad {@code MemoryStack.stackPush()/close()}, 4x {@code Direction.step()} {@code Vector3f}
     * allocations, 4x {@code CapturedRenderingState} queries, off-heap {@code QuadViewEntityUnsafe}
     * readbacks, and per-frame world-space tangent recomputation on immutable {@code BakedQuad}s.
     */
    public static void writeIrisEntityQuad(
            VertexBufferWriter writer,
            PoseStack.Pose matrices,
            me.jellysquid.mods.sodium.client.model.quad.ModelQuadView quad,
            int light,
            int overlay,
            int color) {
        VertexFormatDescription irisFmt = IrisEntityFormatHolder.IRIS_ENTITY_DESC;
        if (irisFmt == null) {
            return;
        }
        short entityId = 0;
        short beId = 0;
        short itemId = 0;
        try {
            Object state = IrisEntityFormatHolder.CAPTURED_STATE;
            entityId = (short) (int) IrisEntityFormatHolder.GET_ENTITY_ID.invokeExact(state);
            beId = (short) (int) IrisEntityFormatHolder.GET_BE_ID.invokeExact(state);
            itemId = (short) (int) IrisEntityFormatHolder.GET_ITEM_ID.invokeExact(state);
        } catch (Throwable ignored) {
        }

        Matrix4f poseMat = matrices.m_252922_();
        Matrix3f normMat = matrices.m_252943_();
        float m00 = poseMat.m00(), m01 = poseMat.m01(), m02 = poseMat.m02();
        float m10 = poseMat.m10(), m11 = poseMat.m11(), m12 = poseMat.m12();
        float m20 = poseMat.m20(), m21 = poseMat.m21(), m22 = poseMat.m22();
        float m30 = poseMat.m30(), m31 = poseMat.m31(), m32 = poseMat.m32();
        float n00 = normMat.m00(), n01 = normMat.m01(), n02 = normMat.m02();
        float n10 = normMat.m10(), n11 = normMat.m11(), n12 = normMat.m12();
        float n20 = normMat.m20(), n21 = normMat.m21(), n22 = normMat.m22();

        // Fast path for BakedQuad (and subclasses) with cached object-space tangent + midUV (+ optional uniform normal)
        if (quad instanceof BakedQuadIrisMeta holder) {
            int[] verts = ((net.minecraft.client.renderer.block.model.BakedQuad) (Object) quad).m_111303_();
            if (verts != null && verts.length >= 32) {
                float[] meta = holder.sunlit$getIrisMeta();
                if (meta == null) {
                    meta = computeBakedQuadIrisMeta(quad);
                    holder.sunlit$setIrisMeta(meta);
                }
                float midU = meta[0], midV = meta[1];
                float otx = meta[2], oty = meta[3], otz = meta[4], oh = meta[5];
                float det3 = m00 * (m11 * m22 - m21 * m12)
                        - m10 * (m01 * m22 - m21 * m02)
                        + m20 * (m01 * m12 - m11 * m02);
                float tx = org.joml.Math.fma(m00, otx, org.joml.Math.fma(m10, oty, m20 * otz));
                float ty = org.joml.Math.fma(m01, otx, org.joml.Math.fma(m11, oty, m21 * otz));
                float tz = org.joml.Math.fma(m02, otx, org.joml.Math.fma(m12, oty, m22 * otz));
                float tLenSq = org.joml.Math.fma(tx, tx, org.joml.Math.fma(ty, ty, tz * tz));
                if (tLenSq > 0.0F) {
                    float invLen = org.joml.Math.invsqrt(tLenSq);
                    tx *= invLen;
                    ty *= invLen;
                    tz *= invLen;
                }
                int tangent = SodiumFastPath.packNormI8(tx, ty, tz, det3 < 0.0F ? -oh : oh);

                if (meta.length == 9) {
                    float onx = meta[6], ony = meta[7], onz = meta[8];
                    float nx = org.joml.Math.fma(n00, onx, org.joml.Math.fma(n10, ony, n20 * onz));
                    float ny = org.joml.Math.fma(n01, onx, org.joml.Math.fma(n11, ony, n21 * onz));
                    float nz = org.joml.Math.fma(n02, onx, org.joml.Math.fma(n12, ony, n22 * onz));
                    int normPacked = NormI8.pack(nx, ny, nz);

                    if (SodiumFastPath.SCRATCH_PTR != 0L && SodiumFastPath.isRenderThread()) {
                        long ptr = SodiumFastPath.SCRATCH_PTR;
                        writeBakedQuadVerts(
                                ptr, verts,
                                m00, m01, m02, m10, m11, m12, m20, m21, m22, m30, m31, m32,
                                color, overlay, light, normPacked, entityId, beId, itemId, midU, midV, tangent);
                        SodiumFastPath.pushScratch(writer, ptr, 4, irisFmt);
                    } else {
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            long ptr = stack.nmalloc(4 * 56);
                            writeBakedQuadVerts(
                                    ptr, verts,
                                    m00, m01, m02, m10, m11, m12, m20, m21, m22, m30, m31, m32,
                                    color, overlay, light, normPacked, entityId, beId, itemId, midU, midV, tangent);
                            writer.push(stack, ptr, 4, irisFmt);
                        }
                    }
                    return;
                } else {
                    net.minecraft.core.Direction d = quad.getLightFace();
                    float dx = d.m_122429_(), dy = d.m_122430_(), dz = d.m_122431_();
                    if (SodiumFastPath.SCRATCH_PTR != 0L && SodiumFastPath.isRenderThread()) {
                        long ptr = SodiumFastPath.SCRATCH_PTR;
                        writeBakedQuadVertsPerVertNorm(
                                ptr, verts, dx, dy, dz,
                                m00, m01, m02, m10, m11, m12, m20, m21, m22, m30, m31, m32,
                                n00, n01, n02, n10, n11, n12, n20, n21, n22,
                                color, overlay, light, entityId, beId, itemId, midU, midV, tangent);
                        SodiumFastPath.pushScratch(writer, ptr, 4, irisFmt);
                    } else {
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            long ptr = stack.nmalloc(4 * 56);
                            writeBakedQuadVertsPerVertNorm(
                                    ptr, verts, dx, dy, dz,
                                    m00, m01, m02, m10, m11, m12, m20, m21, m22, m30, m31, m32,
                                    n00, n01, n02, n10, n11, n12, n20, n21, n22,
                                    color, overlay, light, entityId, beId, itemId, midU, midV, tangent);
                            writer.push(stack, ptr, 4, irisFmt);
                        }
                    }
                    return;
                }
            }
        }

        float u0 = quad.getTexU(0), u1 = quad.getTexU(1), u2 = quad.getTexU(2), u3 = quad.getTexU(3);
        float v0 = quad.getTexV(0), v1 = quad.getTexV(1), v2 = quad.getTexV(2), v3 = quad.getTexV(3);
        float midU = (u0 + u1 + u2 + u3) * 0.25F;
        float midV = (v0 + v1 + v2 + v3) * 0.25F;

        float x0 = quad.getX(0), y0 = quad.getY(0), z0 = quad.getZ(0);
        float wx0 = org.joml.Math.fma(m00, x0, org.joml.Math.fma(m10, y0, org.joml.Math.fma(m20, z0, m30)));
        float wy0 = org.joml.Math.fma(m01, x0, org.joml.Math.fma(m11, y0, org.joml.Math.fma(m21, z0, m31)));
        float wz0 = org.joml.Math.fma(m02, x0, org.joml.Math.fma(m12, y0, org.joml.Math.fma(m22, z0, m32)));

        float x1 = quad.getX(1), y1 = quad.getY(1), z1 = quad.getZ(1);
        float wx1 = org.joml.Math.fma(m00, x1, org.joml.Math.fma(m10, y1, org.joml.Math.fma(m20, z1, m30)));
        float wy1 = org.joml.Math.fma(m01, x1, org.joml.Math.fma(m11, y1, org.joml.Math.fma(m21, z1, m31)));
        float wz1 = org.joml.Math.fma(m02, x1, org.joml.Math.fma(m12, y1, org.joml.Math.fma(m22, z1, m32)));

        float x2 = quad.getX(2), y2 = quad.getY(2), z2 = quad.getZ(2);
        float wx2 = org.joml.Math.fma(m00, x2, org.joml.Math.fma(m10, y2, org.joml.Math.fma(m20, z2, m30)));
        float wy2 = org.joml.Math.fma(m01, x2, org.joml.Math.fma(m11, y2, org.joml.Math.fma(m21, z2, m31)));
        float wz2 = org.joml.Math.fma(m02, x2, org.joml.Math.fma(m12, y2, org.joml.Math.fma(m22, z2, m32)));

        float x3 = quad.getX(3), y3 = quad.getY(3), z3 = quad.getZ(3);
        float wx3 = org.joml.Math.fma(m00, x3, org.joml.Math.fma(m10, y3, org.joml.Math.fma(m20, z3, m30)));
        float wy3 = org.joml.Math.fma(m01, x3, org.joml.Math.fma(m11, y3, org.joml.Math.fma(m21, z3, m31)));
        float wz3 = org.joml.Math.fma(m02, x3, org.joml.Math.fma(m12, y3, org.joml.Math.fma(m22, z3, m32)));

        int fn0 = quad.getForgeNormal(0);
        int fn1 = quad.getForgeNormal(1);
        int fn2 = quad.getForgeNormal(2);
        int fn3 = quad.getForgeNormal(3);

        int np0, np1, np2, np3;
        float lastNx, lastNy, lastNz;
        if (((fn0 | fn1 | fn2 | fn3) & 0xFFFFFF) == 0) {
            net.minecraft.core.Direction d = quad.getLightFace();
            float onx = d.m_122429_();
            float ony = d.m_122430_();
            float onz = d.m_122431_();
            lastNx = org.joml.Math.fma(n00, onx, org.joml.Math.fma(n10, ony, n20 * onz));
            lastNy = org.joml.Math.fma(n01, onx, org.joml.Math.fma(n11, ony, n21 * onz));
            lastNz = org.joml.Math.fma(n02, onx, org.joml.Math.fma(n12, ony, n22 * onz));
            np0 = np1 = np2 = np3 = NormI8.pack(lastNx, lastNy, lastNz);
        } else {
            net.minecraft.core.Direction d = quad.getLightFace();
            float dx = d.m_122429_(), dy = d.m_122430_(), dz = d.m_122431_();
            float onx, ony, onz, nx, ny, nz;

            if ((fn0 & 0xFFFFFF) == 0) { onx = dx; ony = dy; onz = dz; }
            else { onx = NormI8.unpackX(fn0); ony = NormI8.unpackY(fn0); onz = NormI8.unpackZ(fn0); }
            nx = org.joml.Math.fma(n00, onx, org.joml.Math.fma(n10, ony, n20 * onz));
            ny = org.joml.Math.fma(n01, onx, org.joml.Math.fma(n11, ony, n21 * onz));
            nz = org.joml.Math.fma(n02, onx, org.joml.Math.fma(n12, ony, n22 * onz));
            np0 = NormI8.pack(nx, ny, nz);

            if ((fn1 & 0xFFFFFF) == 0) { onx = dx; ony = dy; onz = dz; }
            else { onx = NormI8.unpackX(fn1); ony = NormI8.unpackY(fn1); onz = NormI8.unpackZ(fn1); }
            nx = org.joml.Math.fma(n00, onx, org.joml.Math.fma(n10, ony, n20 * onz));
            ny = org.joml.Math.fma(n01, onx, org.joml.Math.fma(n11, ony, n21 * onz));
            nz = org.joml.Math.fma(n02, onx, org.joml.Math.fma(n12, ony, n22 * onz));
            np1 = NormI8.pack(nx, ny, nz);

            if ((fn2 & 0xFFFFFF) == 0) { onx = dx; ony = dy; onz = dz; }
            else { onx = NormI8.unpackX(fn2); ony = NormI8.unpackY(fn2); onz = NormI8.unpackZ(fn2); }
            nx = org.joml.Math.fma(n00, onx, org.joml.Math.fma(n10, ony, n20 * onz));
            ny = org.joml.Math.fma(n01, onx, org.joml.Math.fma(n11, ony, n21 * onz));
            nz = org.joml.Math.fma(n02, onx, org.joml.Math.fma(n12, ony, n22 * onz));
            np2 = NormI8.pack(nx, ny, nz);

            if ((fn3 & 0xFFFFFF) == 0) { onx = dx; ony = dy; onz = dz; }
            else { onx = NormI8.unpackX(fn3); ony = NormI8.unpackY(fn3); onz = NormI8.unpackZ(fn3); }
            lastNx = org.joml.Math.fma(n00, onx, org.joml.Math.fma(n10, ony, n20 * onz));
            lastNy = org.joml.Math.fma(n01, onx, org.joml.Math.fma(n11, ony, n21 * onz));
            lastNz = org.joml.Math.fma(n02, onx, org.joml.Math.fma(n12, ony, n22 * onz));
            np3 = NormI8.pack(lastNx, lastNy, lastNz);
        }

        int tangent = computeQuadTangentPacked(
                lastNx, lastNy, lastNz,
                wx0, wy0, wz0, u0, v0,
                wx1, wy1, wz1, u1, v1,
                wx2, wy2, wz2, u2, v2);

        int c0 = multARGBIntsFast(quad.getColor(0), color);
        int c1 = multARGBIntsFast(quad.getColor(1), color);
        int c2 = multARGBIntsFast(quad.getColor(2), color);
        int c3 = multARGBIntsFast(quad.getColor(3), color);
        int l0 = me.jellysquid.mods.sodium.client.util.ModelQuadUtil.mergeBakedLight(quad.getLight(0), light);
        int l1 = me.jellysquid.mods.sodium.client.util.ModelQuadUtil.mergeBakedLight(quad.getLight(1), light);
        int l2 = me.jellysquid.mods.sodium.client.util.ModelQuadUtil.mergeBakedLight(quad.getLight(2), light);
        int l3 = me.jellysquid.mods.sodium.client.util.ModelQuadUtil.mergeBakedLight(quad.getLight(3), light);

        if (SodiumFastPath.SCRATCH_PTR != 0L && SodiumFastPath.isRenderThread()) {
            long ptr = SodiumFastPath.SCRATCH_PTR;
            writeIrisQuadVert(ptr, wx0, wy0, wz0, c0, u0, v0, overlay, l0, np0, entityId, beId, itemId, midU, midV, tangent);
            writeIrisQuadVert(ptr + 56L, wx1, wy1, wz1, c1, u1, v1, overlay, l1, np1, entityId, beId, itemId, midU, midV, tangent);
            writeIrisQuadVert(ptr + 112L, wx2, wy2, wz2, c2, u2, v2, overlay, l2, np2, entityId, beId, itemId, midU, midV, tangent);
            writeIrisQuadVert(ptr + 168L, wx3, wy3, wz3, c3, u3, v3, overlay, l3, np3, entityId, beId, itemId, midU, midV, tangent);
            SodiumFastPath.pushScratch(writer, ptr, 4, irisFmt);
        } else {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long ptr = stack.nmalloc(4 * 56);
                writeIrisQuadVert(ptr, wx0, wy0, wz0, c0, u0, v0, overlay, l0, np0, entityId, beId, itemId, midU, midV, tangent);
                writeIrisQuadVert(ptr + 56L, wx1, wy1, wz1, c1, u1, v1, overlay, l1, np1, entityId, beId, itemId, midU, midV, tangent);
                writeIrisQuadVert(ptr + 112L, wx2, wy2, wz2, c2, u2, v2, overlay, l2, np2, entityId, beId, itemId, midU, midV, tangent);
                writeIrisQuadVert(ptr + 168L, wx3, wy3, wz3, c3, u3, v3, overlay, l3, np3, entityId, beId, itemId, midU, midV, tangent);
                writer.push(stack, ptr, 4, irisFmt);
            }
        }
    }

    private static void writeIrisQuadVert(
            long cur,
            float wx, float wy, float wz,
            int color, float u, float vt,
            int overlay, int light, int normPacked,
            short entityId, short beId, short itemId,
            float midU, float midV, int tangent) {
        PositionAttribute.put(cur, wx, wy, wz);
        ColorAttribute.set(cur + 12L, color);
        TextureAttribute.put(cur + 16L, u, vt);
        OverlayAttribute.set(cur + 24L, overlay);
        LightAttribute.set(cur + 28L, light);
        NormalAttribute.set(cur + 32L, normPacked);
        org.lwjgl.system.MemoryUtil.memPutShort(cur + 36L, entityId);
        org.lwjgl.system.MemoryUtil.memPutShort(cur + 38L, beId);
        org.lwjgl.system.MemoryUtil.memPutShort(cur + 40L, itemId);
        org.lwjgl.system.MemoryUtil.memPutFloat(cur + 42L, midU);
        org.lwjgl.system.MemoryUtil.memPutFloat(cur + 46L, midV);
        org.lwjgl.system.MemoryUtil.memPutInt(cur + 50L, tangent);
    }

    private static void writeBakedQuadVerts(
            long cur,
            int[] verts,
            float m00, float m01, float m02,
            float m10, float m11, float m12,
            float m20, float m21, float m22,
            float m30, float m31, float m32,
            int color, int overlay, int light, int normPacked,
            short entityId, short beId, short itemId,
            float midU, float midV, int tangent) {
        for (int b = 0; b < 32; b += 8) {
            float x = Float.intBitsToFloat(verts[b]);
            float y = Float.intBitsToFloat(verts[b + 1]);
            float z = Float.intBitsToFloat(verts[b + 2]);
            float wx = org.joml.Math.fma(m00, x, org.joml.Math.fma(m10, y, org.joml.Math.fma(m20, z, m30)));
            float wy = org.joml.Math.fma(m01, x, org.joml.Math.fma(m11, y, org.joml.Math.fma(m21, z, m31)));
            float wz = org.joml.Math.fma(m02, x, org.joml.Math.fma(m12, y, org.joml.Math.fma(m22, z, m32)));
            int vc = verts[b + 3];
            int c = color == -1 ? vc : (vc == -1 ? color : multARGBIntsFast(vc, color));
            float u = Float.intBitsToFloat(verts[b + 4]);
            float vt = Float.intBitsToFloat(verts[b + 5]);
            int vl = verts[b + 6];
            int l = vl == 0 ? light : me.jellysquid.mods.sodium.client.util.ModelQuadUtil.mergeBakedLight(vl, light);
            writeIrisQuadVert(cur, wx, wy, wz, c, u, vt, overlay, l, normPacked, entityId, beId, itemId, midU, midV, tangent);
            cur += 56L;
        }
    }

    private static void writeBakedQuadVertsPerVertNorm(
            long cur,
            int[] verts,
            float dx, float dy, float dz,
            float m00, float m01, float m02,
            float m10, float m11, float m12,
            float m20, float m21, float m22,
            float m30, float m31, float m32,
            float n00, float n01, float n02,
            float n10, float n11, float n12,
            float n20, float n21, float n22,
            int color, int overlay, int light,
            short entityId, short beId, short itemId,
            float midU, float midV, int tangent) {
        for (int b = 0; b < 32; b += 8) {
            float x = Float.intBitsToFloat(verts[b]);
            float y = Float.intBitsToFloat(verts[b + 1]);
            float z = Float.intBitsToFloat(verts[b + 2]);
            float wx = org.joml.Math.fma(m00, x, org.joml.Math.fma(m10, y, org.joml.Math.fma(m20, z, m30)));
            float wy = org.joml.Math.fma(m01, x, org.joml.Math.fma(m11, y, org.joml.Math.fma(m21, z, m31)));
            float wz = org.joml.Math.fma(m02, x, org.joml.Math.fma(m12, y, org.joml.Math.fma(m22, z, m32)));
            int vc = verts[b + 3];
            int c = color == -1 ? vc : (vc == -1 ? color : multARGBIntsFast(vc, color));
            float u = Float.intBitsToFloat(verts[b + 4]);
            float vt = Float.intBitsToFloat(verts[b + 5]);
            int vl = verts[b + 6];
            int l = vl == 0 ? light : me.jellysquid.mods.sodium.client.util.ModelQuadUtil.mergeBakedLight(vl, light);
            int fn = verts[b + 7];
            float onx, ony, onz;
            if ((fn & 0xFFFFFF) == 0) {
                onx = dx; ony = dy; onz = dz;
            } else {
                onx = NormI8.unpackX(fn); ony = NormI8.unpackY(fn); onz = NormI8.unpackZ(fn);
            }
            float nx = org.joml.Math.fma(n00, onx, org.joml.Math.fma(n10, ony, n20 * onz));
            float ny = org.joml.Math.fma(n01, onx, org.joml.Math.fma(n11, ony, n21 * onz));
            float nz = org.joml.Math.fma(n02, onx, org.joml.Math.fma(n12, ony, n22 * onz));
            int np = NormI8.pack(nx, ny, nz);
            writeIrisQuadVert(cur, wx, wy, wz, c, u, vt, overlay, l, np, entityId, beId, itemId, midU, midV, tangent);
            cur += 56L;
        }
    }

    /**
     * Batched Iris extended-format emitter for Emberbium {@code ItemRenderer.renderBakedItemQuads},
     * reading {@code CapturedRenderingState}, pose/normal matrices, and determinant once per quad
     * list and pushing up to {@code SODIUM_BATCH_QUADS} quads per {@code writer.push()} call.
     */
    public static boolean tryRenderIrisBakedItemQuadsBatch(
            PoseStack.Pose matrices,
            VertexBufferWriter writer,
            java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads,
            net.minecraft.world.item.ItemStack itemStack,
            net.minecraft.client.color.item.ItemColor colorProvider,
            int light,
            int overlay) {
        int size = quads.size();
        if (size < 2
                || !IrisEntityFormatHolder.AVAILABLE
                || IrisEntityFormatHolder.IS_SHADER_IN_USE == null
                || IrisEntityFormatHolder.GET_EXTENDED_VF == null
                || SodiumFastPath.SCRATCH_PTR == 0L
                || !SodiumFastPath.isRenderThread()) {
            return false;
        }
        try {
            if (!(boolean) IrisEntityFormatHolder.IS_SHADER_IN_USE.invokeExact(IrisEntityFormatHolder.IRIS_API_INSTANCE)
                    || !(boolean) IrisEntityFormatHolder.GET_EXTENDED_VF.invokeExact()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }

        VertexFormatDescription irisFmt = IrisEntityFormatHolder.IRIS_ENTITY_DESC;
        short entityId = 0;
        short beId = 0;
        short itemId = 0;
        try {
            Object state = IrisEntityFormatHolder.CAPTURED_STATE;
            entityId = (short) (int) IrisEntityFormatHolder.GET_ENTITY_ID.invokeExact(state);
            beId = (short) (int) IrisEntityFormatHolder.GET_BE_ID.invokeExact(state);
            itemId = (short) (int) IrisEntityFormatHolder.GET_ITEM_ID.invokeExact(state);
        } catch (Throwable ignored) {
        }

        Matrix4f poseMat = matrices.m_252922_();
        Matrix3f normMat = matrices.m_252943_();
        float m00 = poseMat.m00(), m01 = poseMat.m01(), m02 = poseMat.m02();
        float m10 = poseMat.m10(), m11 = poseMat.m11(), m12 = poseMat.m12();
        float m20 = poseMat.m20(), m21 = poseMat.m21(), m22 = poseMat.m22();
        float m30 = poseMat.m30(), m31 = poseMat.m31(), m32 = poseMat.m32();
        float n00 = normMat.m00(), n01 = normMat.m01(), n02 = normMat.m02();
        float n10 = normMat.m10(), n11 = normMat.m11(), n12 = normMat.m12();
        float n20 = normMat.m20(), n21 = normMat.m21(), n22 = normMat.m22();
        float det3 = m00 * (m11 * m22 - m21 * m12)
                - m10 * (m01 * m22 - m21 * m02)
                + m20 * (m01 * m12 - m11 * m02);
        float detFlip = det3 < 0.0F ? -1.0F : 1.0F;

        long basePtr = SodiumFastPath.SCRATCH_PTR;
        long cur = basePtr;
        int batchQuads = 0;

        for (int i = 0; i < size; i++) {
            net.minecraft.client.renderer.block.model.BakedQuad bq = quads.get(i);
            int[] verts = bq.m_111303_();
            if (verts == null || verts.length < 32) {
                continue;
            }
            me.jellysquid.mods.sodium.client.model.quad.BakedQuadView qv =
                    (me.jellysquid.mods.sodium.client.model.quad.BakedQuadView) bq;
            int color = -1;
            if (colorProvider != null && qv.hasColor()) {
                color = net.caffeinemc.mods.sodium.api.util.ColorARGB.toABGR(
                        colorProvider.m_92671_(itemStack, qv.getColorIndex()), 255);
            }
            if (bq instanceof BakedQuadIrisMeta holder) {
                float[] meta = holder.sunlit$getIrisMeta();
                if (meta == null) {
                    meta = computeBakedQuadIrisMeta(qv);
                    holder.sunlit$setIrisMeta(meta);
                }
                float midU = meta[0], midV = meta[1];
                float otx = meta[2], oty = meta[3], otz = meta[4], oh = meta[5];
                float tx = org.joml.Math.fma(m00, otx, org.joml.Math.fma(m10, oty, m20 * otz));
                float ty = org.joml.Math.fma(m01, otx, org.joml.Math.fma(m11, oty, m21 * otz));
                float tz = org.joml.Math.fma(m02, otx, org.joml.Math.fma(m12, oty, m22 * otz));
                float tLenSq = org.joml.Math.fma(tx, tx, org.joml.Math.fma(ty, ty, tz * tz));
                if (tLenSq > 0.0F) {
                    float invLen = org.joml.Math.invsqrt(tLenSq);
                    tx *= invLen;
                    ty *= invLen;
                    tz *= invLen;
                }
                int tangent = SodiumFastPath.packNormI8(tx, ty, tz, oh * detFlip);

                if (meta.length == 9) {
                    float onx = meta[6], ony = meta[7], onz = meta[8];
                    float nx = org.joml.Math.fma(n00, onx, org.joml.Math.fma(n10, ony, n20 * onz));
                    float ny = org.joml.Math.fma(n01, onx, org.joml.Math.fma(n11, ony, n21 * onz));
                    float nz = org.joml.Math.fma(n02, onx, org.joml.Math.fma(n12, ony, n22 * onz));
                    int normPacked = NormI8.pack(nx, ny, nz);
                    writeBakedQuadVerts(
                            cur, verts,
                            m00, m01, m02, m10, m11, m12, m20, m21, m22, m30, m31, m32,
                            color, overlay, light, normPacked, entityId, beId, itemId, midU, midV, tangent);
                } else {
                    net.minecraft.core.Direction d = qv.getLightFace();
                    writeBakedQuadVertsPerVertNorm(
                            cur, verts, d.m_122429_(), d.m_122430_(), d.m_122431_(),
                            m00, m01, m02, m10, m11, m12, m20, m21, m22, m30, m31, m32,
                            n00, n01, n02, n10, n11, n12, n20, n21, n22,
                            color, overlay, light, entityId, beId, itemId, midU, midV, tangent);
                }
                cur += 224L;
                if (++batchQuads == SODIUM_BATCH_QUADS) {
                    SodiumFastPath.pushScratch(writer, basePtr, batchQuads << 2, irisFmt);
                    batchQuads = 0;
                    cur = basePtr;
                }
                me.jellysquid.mods.sodium.client.render.texture.SpriteUtil.markSpriteActive(qv.getSprite());
                continue;
            }
            if (batchQuads > 0) {
                SodiumFastPath.pushScratch(writer, basePtr, batchQuads << 2, irisFmt);
                batchQuads = 0;
                cur = basePtr;
            }
            writeIrisEntityQuad(writer, matrices, qv, light, overlay, color);
            me.jellysquid.mods.sodium.client.render.texture.SpriteUtil.markSpriteActive(qv.getSprite());
        }

        if (batchQuads > 0) {
            SodiumFastPath.pushScratch(writer, basePtr, batchQuads << 2, irisFmt);
        }
        return true;
    }

    private static final java.util.function.Predicate<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>
            HAS_AMBIENT_BIOME_PARTICLE = h -> h != null && h.m_203633_() && h.m_203334_().m_47562_().isPresent();
    private static java.lang.ref.WeakReference<net.minecraft.client.multiplayer.ClientLevel> ambientCacheLevel;
    private static int ambientCacheChunkX = Integer.MIN_VALUE;
    private static int ambientCacheChunkZ = Integer.MIN_VALUE;
    private static long ambientCacheScanTick = Long.MIN_VALUE;
    private static long ambientCacheVerifiedTick = Long.MIN_VALUE;
    private static boolean ambientCacheHasParticles = true;

    /**
     * Fast path for {@code ClientLevel.doAnimateTick}'s ambient biome particle check: skips
     * ~18,000/sec {@code BiomeManager.getBiome} Voronoi hashes and lambda allocations whenever
     * no biome in the 7x7 chunk neighborhood around the player has ambient particles.
     */
    public static void maybeSpawnBiomeAmbientParticle(
            net.minecraft.client.multiplayer.ClientLevel level,
            net.minecraft.core.BlockPos.MutableBlockPos pos) {
        long gameTime = level.m_46467_();
        net.minecraft.client.multiplayer.ClientLevel cachedLvl =
                ambientCacheLevel == null ? null : ambientCacheLevel.get();
        if (cachedLvl == level && gameTime == ambientCacheVerifiedTick) {
            if (!ambientCacheHasParticles) {
                return;
            }
        } else {
            net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.m_91087_().f_91074_;
            int centerCx = player != null ? (net.minecraft.util.Mth.m_14107_(player.m_20185_()) >> 4) : (pos.m_123341_() >> 4);
            int centerCz = player != null ? (net.minecraft.util.Mth.m_14107_(player.m_20189_()) >> 4) : (pos.m_123343_() >> 4);
            if (cachedLvl != level
                    || centerCx != ambientCacheChunkX
                    || centerCz != ambientCacheChunkZ
                    || (gameTime - ambientCacheScanTick) >= 40L
                    || gameTime < ambientCacheScanTick) {
                ambientCacheLevel = new java.lang.ref.WeakReference<>(level);
                ambientCacheChunkX = centerCx;
                ambientCacheChunkZ = centerCz;
                ambientCacheScanTick = gameTime;
                ambientCacheHasParticles = scanNeighborhoodForAmbientParticles(level, centerCx, centerCz);
            }
            ambientCacheVerifiedTick = gameTime;
            if (!ambientCacheHasParticles) {
                return;
            }
        }
        java.util.Optional<net.minecraft.world.level.biome.AmbientParticleSettings> opt =
                level.m_204166_(pos).m_203334_().m_47562_();
        if (opt.isPresent()) {
            net.minecraft.world.level.biome.AmbientParticleSettings settings = opt.get();
            net.minecraft.util.RandomSource rand = level.f_46441_;
            if (settings.m_220527_(rand)) {
                level.m_7106_(
                        settings.m_47419_(),
                        (double) pos.m_123341_() + rand.m_188500_(),
                        (double) pos.m_123342_() + rand.m_188500_(),
                        (double) pos.m_123343_() + rand.m_188500_(),
                        0.0D, 0.0D, 0.0D);
            }
        }
    }

    private static boolean scanNeighborhoodForAmbientParticles(
            net.minecraft.client.multiplayer.ClientLevel level,
            int centerCx,
            int centerCz) {
        net.minecraft.client.multiplayer.ClientChunkCache chunkSource = level.m_7726_();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk =
                        chunkSource.m_62227_(centerCx + dx, centerCz + dz, false);
                if (chunk == null || chunk instanceof net.minecraft.world.level.chunk.EmptyLevelChunk) {
                    return true;
                }
                net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.m_7103_();
                for (net.minecraft.world.level.chunk.LevelChunkSection section : sections) {
                    if (section != null && section.m_187996_().m_63109_(HAS_AMBIENT_BIOME_PARTICLE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Reusable per-BufferBuilder {@link Vector3f} pool for {@code BufferBuilder.makeQuadSortingPoints}
     * ({@code m_166794_}), eliminating ~340 MB of per-frame {@code Vector3f} churn while preserving
     * exact midpoint math and isolating {@code getSortState()} snapshots.
     */
    public static final class QuadSortPool {
        private Vector3f[] pool = new Vector3f[0];
        private Vector3f[] lastView = new Vector3f[0];

        Vector3f[] populate(java.nio.ByteBuffer buf, int renderedBufferPointer, int vertexIntSize, int primLen, int vertices) {
            int quadCount = vertices / primLen;
            if (quadCount <= 0) {
                return new Vector3f[0];
            }
            Vector3f[] p = this.pool;
            if (p.length < quadCount) {
                int newCap = Math.max(quadCount, Math.max(64, p.length << 1));
                Vector3f[] next = java.util.Arrays.copyOf(p, newCap);
                for (int i = p.length; i < newCap; i++) {
                    next[i] = new Vector3f();
                }
                p = next;
                this.pool = p;
            }
            int vertBytes = vertexIntSize << 2;
            int quadBytes = vertBytes * primLen;
            int v2Offset = vertBytes << 1;
            if (buf.isDirect()) {
                long base = org.lwjgl.system.MemoryUtil.memAddress(buf) + renderedBufferPointer;
                for (int i = 0; i < quadCount; i++) {
                    long q = base + (long) i * quadBytes;
                    float x0 = org.lwjgl.system.MemoryUtil.memGetFloat(q);
                    float y0 = org.lwjgl.system.MemoryUtil.memGetFloat(q + 4L);
                    float z0 = org.lwjgl.system.MemoryUtil.memGetFloat(q + 8L);
                    float x2 = org.lwjgl.system.MemoryUtil.memGetFloat(q + v2Offset);
                    float y2 = org.lwjgl.system.MemoryUtil.memGetFloat(q + v2Offset + 4L);
                    float z2 = org.lwjgl.system.MemoryUtil.memGetFloat(q + v2Offset + 8L);
                    p[i].set((x0 + x2) * 0.5F, (y0 + y2) * 0.5F, (z0 + z2) * 0.5F);
                }
            } else {
                for (int i = 0; i < quadCount; i++) {
                    int q = renderedBufferPointer + i * quadBytes;
                    float x0 = buf.getFloat(q);
                    float y0 = buf.getFloat(q + 4);
                    float z0 = buf.getFloat(q + 8);
                    float x2 = buf.getFloat(q + v2Offset);
                    float y2 = buf.getFloat(q + v2Offset + 4);
                    float z2 = buf.getFloat(q + v2Offset + 8);
                    p[i].set((x0 + x2) * 0.5F, (y0 + y2) * 0.5F, (z0 + z2) * 0.5F);
                }
            }
            Vector3f[] view = this.lastView;
            if (view.length != quadCount) {
                view = java.util.Arrays.copyOf(p, quadCount);
                this.lastView = view;
            }
            return view;
        }
    }

    public static Object newQuadSortPool() {
        return new QuadSortPool();
    }

    public static Vector3f[] makeQuadSortingPointsPooled(
            java.nio.ByteBuffer buf,
            int renderedBufferPointer,
            int vertexIntSize,
            int primLen,
            int vertices,
            Object poolObj) {
        QuadSortPool pool = poolObj instanceof QuadSortPool qsp ? qsp : new QuadSortPool();
        return pool.populate(buf, renderedBufferPointer, vertexIntSize, primLen, vertices);
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

    private static final NumberFormatException FAST_NON_FLOAT_NFE = new NumberFormatException("non-float token") {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    };

    public static float parseFloatOrThrowFast(String token) {
        if (!canBeFloat(token)) {
            throw FAST_NON_FLOAT_NFE;
        }
        return Float.parseFloat(token);
    }

    public static boolean canBeFloat(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        char c = token.charAt(0);
        if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+' || c <= ' ') {
            return true;
        }
        return (c == 'N' && "NaN".equals(token)) || (c == 'I' && "Infinity".equals(token));
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
