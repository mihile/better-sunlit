package dev.sunlit.fishvisuals.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sunlit.fishvisuals.CatchModelIds;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small, original meshes in model pixels, with the nose facing positive Z. */
final class CustomCatchModel {
    private static final float PIXEL = 1f / 16;
    private static final ResourceLocation WHITE = new ResourceLocation("better_sunlit:textures/fishing/white.png");
    private static final Map<ResourceLocation, CustomCatchModel> MODELS = Map.of(
            CatchModelIds.LEECH, leech(),
            CatchModelIds.GOLDFISH, goldfish(),
            CatchModelIds.NEPTUNA, neptuna());

    private final List<Face> faces;
    private final float size;
    private final float restRoll;

    private CustomCatchModel(List<Face> faces, float length, float restRoll) {
        this.faces = List.copyOf(faces);
        this.size = length * PIXEL;
        this.restRoll = (float)Math.toRadians(restRoll);
    }

    static CustomCatchModel get(ResourceLocation id) { return MODELS.get(id); }

    float size() { return size; }

    void draw(PoseStack pose, MultiBufferSource buffers, int light, float yaw, float settled) {
        float roll = restRoll * settled;
        float sin = (float)Math.sin(roll), cos = (float)Math.cos(roll);
        float bottom = Float.POSITIVE_INFINITY;
        for (Face face : faces) {
            for (Point p : face.points) bottom = Math.min(bottom, p.x * sin + p.y * cos);
        }
        // Let the fish come to rest on its side while keeping the lowest fin above the ground.
        pose.m_252880_(0, -bottom * PIXEL + 0.012f, 0);
        pose.m_252781_(new Quaternionf().rotationY((float)Math.toRadians(-yaw)).rotateZ(roll));
        var consumer = buffers.m_6299_(RenderType.m_110452_(WHITE));
        var positionMatrix = pose.m_85850_().m_252922_();
        var normalMatrix = pose.m_85850_().m_252943_();
        Vector3f position = new Vector3f(), normal = new Vector3f();
        for (Face face : faces) {
            normalMatrix.transform(face.normal, normal).normalize();
            float red = ((face.color >> 16) & 255) / 255f;
            float green = ((face.color >> 8) & 255) / 255f;
            float blue = (face.color & 255) / 255f;
            for (Point p : face.points) {
                positionMatrix.transformPosition(p.x * PIXEL, p.y * PIXEL, p.z * PIXEL, position);
                consumer.m_5954_(position.x, position.y, position.z, red, green, blue, 1, 0.5f, 0.5f,
                        OverlayTexture.f_118083_, light, normal.x, normal.y, normal.z);
            }
        }
    }

    private static CustomCatchModel leech() {
        var faces = new ArrayList<Face>();
        Ring[] rings = {
                new Ring(5.2f, .60f, .32f, .03f, 0x74615d),
                new Ring(4.7f, .81f, .44f, .03f, 0x4c4140),
                new Ring(3.5f, .70f, .40f, 0, 0x69514a),
                new Ring(2.7f, .90f, .48f, 0, 0x554646),
                new Ring(1.8f, .83f, .43f, 0, 0x75615a),
                new Ring(.8f, 1.04f, .49f, 0, 0x554848),
                new Ring(-.2f, .93f, .42f, 0, 0x6f5551),
                new Ring(-1.2f, 1.03f, .47f, 0, 0x504243),
                new Ring(-2.2f, .88f, .37f, 0, 0x634f4b),
                new Ring(-3.2f, .70f, .29f, -.07f, 0x4a3f40),
                new Ring(-4.35f, .48f, .22f, -.12f, 0x66524f),
                new Ring(-4.8f, .76f, .23f, -.14f, 0x514242),
                new Ring(-5.4f, .68f, .18f, -.16f, 0x594748)
        };
        body(faces, rings, 0xc1a098, 0x755d5c);
        quad(faces, 0x563937, p(-.22f,-.09f,5.21f), p(.22f,-.09f,5.21f),
                p(.2f,.14f,5.21f), p(-.2f,.14f,5.21f));
        return new CustomCatchModel(faces, 10.6f, 0);
    }

    private static CustomCatchModel goldfish() {
        var faces = new ArrayList<Face>();
        Ring[] rings = {
                new Ring(4.0f, .63f, .55f, -.16f, 0xe98b20),
                new Ring(3.12f, 1.02f, 1.06f, -.04f, 0xffb93b),
                new Ring(1.91f, 1.63f, 1.67f, .20f, 0xffc756),
                new Ring(.15f, 1.65f, 1.80f, .19f, 0xed952e),
                new Ring(-1.5f, 1.14f, 1.20f, .07f, 0xf3a13a),
                new Ring(-3.5f, .34f, .50f, .01f, 0xd76222),
                new Ring(-4.3f, .24f, .34f, .01f, 0xe27b27)
        };
        body(faces, rings, 0xf2a23b, 0xea7625);
        eyes(faces, rings[1], rings[2], 0xffd855, 0x2e1e1e);
        blade(faces, 0xfa8a27, p(0,.29f,-3.9f), p(0,1.8f,-6.5f), p(0,1.45f,-7.5f), p(0,-.02f,-6.25f));
        blade(faces, 0xf3a044, p(0,-.22f,-3.9f), p(0,-.02f,-6.25f), p(0,-1.30f,-7.4f), p(0,-1.55f,-6.55f));
        blade(faces, 0xe77723, p(0,1.5f,1.9f), p(0,2.65f,1.05f), p(0,2.43f,-.44f), p(0,1.15f,-1.88f));
        pairedFins(faces, 0xe07a27, 1.41f, -.19f, 1.93f, 1.9f, -1.01f);
        return new CustomCatchModel(faces, 11.5f, 78);
    }

    private static CustomCatchModel neptuna() {
        var faces = new ArrayList<Face>();
        Ring[] rings = {
                new Ring(6.2f, .42f, .36f, -.11f, 0x1e9a83),
                new Ring(4.8f, .79f, .78f, 0, 0x147f70),
                new Ring(2.7f, 1.27f, 1.36f, .07f, 0x157967),
                new Ring(.3f, 1.32f, 1.39f, .04f, 0x177d73),
                new Ring(-2.1f, .94f, 1.07f, .04f, 0x116853),
                new Ring(-4.6f, .46f, .58f, .07f, 0x127666),
                new Ring(-5.85f, .24f, .31f, .09f, 0x16634e)
        };
        body(faces, rings, 0x56ddc3, 0x0e624c);
        eyes(faces, rings[1], rings[2], 0x93f6de, 0x0f2f31);
        for (int i = 2; i < rings.length - 1; i++) {
            decal(faces, rings[i], rings[i + 1], 1, .48f, .67f, 0, 1, 0x44c7ad, .02f);
            decal(faces, rings[i], rings[i + 1], 5, .33f, .52f, 0, 1, 0x44c7ad, -.02f);
        }
        blade(faces, 0x1eae8d, p(0,.24f,-5.6f), p(0,1.82f,-8.35f), p(0,1.80f,-9.1f), p(0,.08f,-7.4f));
        blade(faces, 0x138f7c, p(0,-.20f,-5.6f), p(0,.08f,-7.4f), p(0,-1.53f,-9.1f), p(0,-1.60f,-8.45f));
        blade(faces, 0x177b6c, p(0,1.07f,2.9f), p(0,2.24f,1.03f), p(0,1.71f,-.20f), p(0,1.14f,-1.6f));
        blade(faces, 0x197e72, p(0,.75f,-2.8f), p(0,1.18f,-3.42f), p(0,1.02f,-4.36f), p(0,.5f,-4.72f));
        pairedFins(faces, 0x29a58f, 1.21f, -.18f, 2.5f, 2.2f, -1.6f);
        return new CustomCatchModel(faces, 15.3f, 80);
    }

    private static void body(List<Face> faces, Ring[] rings, int nose, int back) {
        cap(faces, rings[0], nose, true);
        for (int j = 0; j + 1 < rings.length; j++) {
            Ring first = rings[j], next = rings[j+1];
            for (int i = 0; i < 8; i++) {
                int following = (i + 1) % 8;
                float shade = switch (i) {
                    case 7 -> .81f;
                    case 0, 6 -> .91f;
                    case 3 -> 1.23f;
                    case 2, 4 -> 1.12f;
                    default -> 1;
                };
                quad(faces, shade(first.color, shade), first.point(i), first.point(following),
                        next.point(following), next.point(i));
            }
        }
        cap(faces, rings[rings.length - 1], back, false);
    }

    private static void cap(List<Face> faces, Ring ring, int color, boolean front) {
        Point center = p(0, ring.y, ring.z);
        for (int i = 0; i < 8; i++) {
            Point a = ring.point(i), b = ring.point((i + 1) % 8);
            quad(faces, color, center, front ? b : a, front ? a : b, front ? a : b);
        }
    }

    private static void pairedFins(List<Face> faces, int color, float x, float y, float z, float tip, float rear) {
        for (int side : new int[]{-1, 1}) {
            blade(faces, color, p(side*x,y,z), p(side*tip,y-.54f,z-.75f),
                    p(side*tip,y-.64f,rear), p(side*x,y-.43f,z-1.22f));
        }
    }

    private static void eyes(List<Face> faces, Ring nose, Ring rear, int iris, int pupil) {
        for (int side : new int[]{-1, 1}) {
            int face = side > 0 ? 1 : 5;
            decal(faces, nose, rear, face, .12f, .81f, .13f, .71f, iris, side*.025f);
            decal(faces, nose, rear, face, .26f, .66f, .20f, .52f, pupil, side*.038f);
            decal(faces, nose, rear, face, .34f, .53f, .23f, .34f, 0xfff3cf, side*.049f);
        }
    }

    private static void decal(List<Face> faces, Ring a, Ring b, int edge,
            float u0, float u1, float v0, float v1, int color, float offsetX) {
        int next = (edge + 1) % 8;
        Point f0 = a.point(edge).lerp(a.point(next), u0), f1 = a.point(edge).lerp(a.point(next), u1);
        Point r0 = b.point(edge).lerp(b.point(next), u0), r1 = b.point(edge).lerp(b.point(next), u1);
        quad(faces, color, f0.lerp(r0,v0).offsetX(offsetX), f1.lerp(r1,v0).offsetX(offsetX),
                f1.lerp(r1,v1).offsetX(offsetX), f0.lerp(r0,v1).offsetX(offsetX));
    }

    private static void blade(List<Face> faces, int color, Point a, Point b, Point c, Point d) {
        quad(faces, color, a, b, c, d);
        quad(faces, color, d, c, b, a);
    }

    private static void quad(List<Face> faces, int color, Point a, Point b, Point c, Point d) {
        Vector3f normal = new Vector3f(b.x-a.x,b.y-a.y,b.z-a.z)
                .cross(c.x-a.x,c.y-a.y,c.z-a.z).normalize();
        faces.add(new Face(new Point[]{a,b,c,d}, normal, color));
    }

    private static int shade(int color, float scale) {
        return Math.min(255, (int)(((color >> 16) & 255) * scale)) << 16
                | Math.min(255, (int)(((color >> 8) & 255) * scale)) << 8
                | Math.min(255, (int)((color & 255) * scale));
    }

    private static Point p(float x, float y, float z) { return new Point(x, y, z); }

    private record Point(float x, float y, float z) {
        Point lerp(Point other, float t) {
            return p(x+(other.x-x)*t, y+(other.y-y)*t, z+(other.z-z)*t);
        }
        Point offsetX(float offset) { return p(x+offset,y,z); }
    }

    private record Face(Point[] points, Vector3f normal, int color) {}

    private record Ring(float z, float width, float height, float y, int color) {
        private static final float[] X = {.38f, 1, 1, .38f, -.38f, -1, -1, -.38f};
        private static final float[] Y = {1, .48f, -.48f, -1, -1, -.48f, .48f, 1};

        Point point(int i) { return p(X[i]*width, y+Y[i]*height, z); }
    }
}
