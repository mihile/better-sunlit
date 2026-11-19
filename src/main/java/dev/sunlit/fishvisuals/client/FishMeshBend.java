package dev.sunlit.fishvisuals.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Splits the rendered fish surface at three points along its length before bending it. */
final class FishMeshBend implements MultiBufferSource {
    private static final int SECTIONS = 4;
    private final MultiBufferSource output;
    private final Vector3f origin;
    private final Vector3f lateral;
    private final Vector3f up;
    private final Vector3f towardTail;
    private final List<Layer> layers = new ArrayList<>();
    private float head = Float.POSITIVE_INFINITY;
    private float tail = Float.NEGATIVE_INFINITY;

    FishMeshBend(MultiBufferSource output, Matrix4f matrix, float yaw) {
        this.output = output;
        origin = matrix.transformPosition(0, 0, 0, new Vector3f());
        up = matrix.transformDirection(0, 1, 0, new Vector3f()).normalize();
        float heading = (float)Math.toRadians(yaw);
        var rear = matrix.transformDirection((float)Math.sin(heading), 0, -(float)Math.cos(heading), new Vector3f());
        lateral = up.cross(rear, new Vector3f()).normalize();
        towardTail = lateral.cross(up, new Vector3f());
    }

    @Override
    public VertexConsumer m_6299_(RenderType type) {
        if (type.m_173186_() != VertexFormat.Mode.QUADS || type.m_110508_() != DefaultVertexFormat.f_85812_) {
            return output.m_6299_(type);
        }
        Layer layer = new Layer(type);
        layers.add(layer);
        return layer;
    }

    void draw(FishFlopMotion motion) {
        var curve = Float.isFinite(head) && tail - head > 1.0e-4f ? new Curve(head, tail, motion) : null;
        for (Layer layer : layers) {
            if (layer.vertices.isEmpty()) continue;
            VertexConsumer consumer = output.m_6299_(layer.type);
            if (curve == null || layer.vertices.size() % 4 != 0) {
                for (Vertex vertex : layer.vertices) emit(consumer, vertex, null);
                continue;
            }
            for (int i = 0; i < layer.vertices.size(); i += 4) {
                List<Vertex> quad = layer.vertices.subList(i, i + 4);
                splitAndDraw(consumer, quad, curve);
            }
        }
    }

    private void splitAndDraw(VertexConsumer output, List<Vertex> quad, Curve curve) {
        float first = Float.POSITIVE_INFINITY;
        float last = Float.NEGATIVE_INFINITY;
        for (Vertex vertex : quad) {
            first = Math.min(first, vertex.z);
            last = Math.max(last, vertex.z);
        }
        int firstSection = curve.section(first);
        int lastSection = curve.section(last);
        for (int section = firstSection; section <= lastSection; section++) {
            List<Vertex> band = quad;
            if (section > firstSection) band = clip(band, curve.boundary(section), true);
            if (section < lastSection) band = clip(band, curve.boundary(section + 1), false);
            if (band.size() == 4) {
                for (Vertex vertex : band) emit(output, vertex, curve);
            } else {
                // QUADS treats the repeated final vertex as a degenerate second triangle.
                for (int i = 1; i + 1 < band.size(); i++) {
                    emit(output, band.get(0), curve);
                    emit(output, band.get(i), curve);
                    emit(output, band.get(i + 1), curve);
                    emit(output, band.get(i + 1), curve);
                }
            }
        }
    }

    private static List<Vertex> clip(List<Vertex> polygon, float plane, boolean keepRear) {
        if (polygon.isEmpty()) return polygon;
        List<Vertex> result = new ArrayList<>(6);
        Vertex previous = polygon.get(polygon.size() - 1);
        for (Vertex vertex : polygon) {
            boolean inside = keepRear ? vertex.z >= plane : vertex.z <= plane;
            boolean previousInside = keepRear ? previous.z >= plane : previous.z <= plane;
            if (inside != previousInside && previous.z != plane && vertex.z != plane) {
                result.add(previous.atPlane(vertex, plane));
            }
            if (inside) result.add(vertex);
            previous = vertex;
        }
        return result;
    }

    private void emit(VertexConsumer consumer, Vertex vertex, Curve curve) {
        float x = vertex.x, z = vertex.z, nx = vertex.nx, nz = vertex.nz;
        if (curve != null) {
            int section = curve.section(z);
            float progress = Math.max(0, Math.min(1, (z - curve.boundary(section)) / curve.step));
            float angle = lerp(curve.heading[section], curve.heading[section + 1], progress);
            float sin = (float)Math.sin(angle), cos = (float)Math.cos(angle);
            x = lerp(curve.x[section], curve.x[section + 1], progress) + vertex.x * cos;
            z = lerp(curve.z[section], curve.z[section + 1], progress) - vertex.x * sin;
            nx = vertex.nx * cos + vertex.nz * sin;
            nz = vertex.nz * cos - vertex.nx * sin;
        }
        consumer.m_5954_(
                origin.x + lateral.x * x + up.x * vertex.y + towardTail.x * z,
                origin.y + lateral.y * x + up.y * vertex.y + towardTail.y * z,
                origin.z + lateral.z * x + up.z * vertex.y + towardTail.z * z,
                vertex.r, vertex.g, vertex.b, vertex.a, vertex.u, vertex.v, vertex.overlay, vertex.light,
                lateral.x * nx + up.x * vertex.ny + towardTail.x * nz,
                lateral.y * nx + up.y * vertex.ny + towardTail.y * nz,
                lateral.z * nx + up.z * vertex.ny + towardTail.z * nz);
    }

    private static float lerp(float from, float to, float t) { return from + (to - from) * t; }

    private static final class Curve {
        final float head, step;
        final float[] heading, x = new float[SECTIONS + 1], z = new float[SECTIONS + 1];

        Curve(float head, float tail, FishFlopMotion motion) {
            this.head = head;
            step = (tail - head) / SECTIONS;
            heading = new float[]{motion.front(), (motion.front() + motion.rear()) / 2,
                    (motion.rear() + motion.tail()) / 2, (motion.tail() + motion.tip()) / 2, motion.tip()};
            z[0] = head;
            for (int i = 1; i <= SECTIONS; i++) {
                double angle = (heading[i - 1] + heading[i]) / 2;
                x[i] = x[i - 1] + step * (float)Math.sin(angle);
                z[i] = z[i - 1] + step * (float)Math.cos(angle);
            }
        }

        int section(float depth) {
            return Math.min(SECTIONS - 1, Math.max(0, (int)((depth - head) / step)));
        }

        float boundary(int section) { return head + section * step; }
    }

    private record Vertex(float x, float y, float z, float r, float g, float b, float a, float u, float v,
                          int overlay, int light, float nx, float ny, float nz) {
        Vertex atPlane(Vertex other, float plane) {
            float t = (plane - z) / (other.z - z);
            return new Vertex(lerp(x, other.x, t), lerp(y, other.y, t), plane,
                    lerp(r, other.r, t), lerp(g, other.g, t), lerp(b, other.b, t), lerp(a, other.a, t),
                    lerp(u, other.u, t), lerp(v, other.v, t), t < .5f ? overlay : other.overlay,
                    t < .5f ? light : other.light, lerp(nx, other.nx, t), lerp(ny, other.ny, t), lerp(nz, other.nz, t));
        }
    }

    private final class Layer implements VertexConsumer {
        final RenderType type;
        final List<Vertex> vertices = new ArrayList<>();
        float x, y, z, u, v, nx, ny, nz;
        float r = 1, g = 1, b = 1, a = 1;
        float defaultR = 1, defaultG = 1, defaultB = 1, defaultA = 1;
        int overlay, light;

        Layer(RenderType type) { this.type = type; }

        @Override
        public void m_5954_(float x, float y, float z, float r, float g, float b, float a, float u, float v,
                            int overlay, int light, float nx, float ny, float nz) {
            float dx = x - origin.x, dy = y - origin.y, dz = z - origin.z;
            float depth = towardTail.dot(dx, dy, dz);
            head = Math.min(head, depth);
            tail = Math.max(tail, depth);
            vertices.add(new Vertex(lateral.dot(dx, dy, dz), up.dot(dx, dy, dz), depth, r, g, b, a, u, v,
                    overlay, light, lateral.dot(nx, ny, nz), up.dot(nx, ny, nz), towardTail.dot(nx, ny, nz)));
        }

        @Override public VertexConsumer m_5483_(double x, double y, double z) {
            this.x = (float)x;
            this.y = (float)y;
            this.z = (float)z;
            return this;
        }

        @Override public VertexConsumer m_6122_(int r, int g, int b, int a) {
            this.r = r / 255f;
            this.g = g / 255f;
            this.b = b / 255f;
            this.a = a / 255f;
            return this;
        }

        @Override public VertexConsumer m_7421_(float u, float v) { this.u = u; this.v = v; return this; }
        @Override public VertexConsumer m_7122_(int u, int v) { overlay = (u & 0xffff) | (v << 16); return this; }
        @Override public VertexConsumer m_7120_(int u, int v) { light = (u & 0xffff) | (v << 16); return this; }
        @Override public VertexConsumer m_5601_(float x, float y, float z) { nx = x; ny = y; nz = z; return this; }

        @Override public void m_5752_() {
            m_5954_(x, y, z, r, g, b, a, u, v, overlay, light, nx, ny, nz);
            r = defaultR;
            g = defaultG;
            b = defaultB;
            a = defaultA;
        }

        @Override public void m_7404_(int r, int g, int b, int a) {
            defaultR = r / 255f;
            defaultG = g / 255f;
            defaultB = b / 255f;
            defaultA = a / 255f;
            m_6122_(r, g, b, a);
        }

        @Override public void m_141991_() { m_7404_(255, 255, 255, 255); }
    }
}
