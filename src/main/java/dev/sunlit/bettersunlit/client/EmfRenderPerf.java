package dev.sunlit.bettersunlit.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.embeddedt.embeddium.render.matrix_stack.CachingPoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

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
    }

    private static final MethodHandle GET_POLY_NORMAL;
    private static final MethodHandle GET_POLY_VERTICES;
    private static final MethodHandle GET_VERT_POS;
    private static final MethodHandle GET_VERT_U;
    private static final MethodHandle GET_VERT_V;

    static {
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
}
