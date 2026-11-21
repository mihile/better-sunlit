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

/** Textured catch meshes measured in model pixels, with the head facing positive Z. */
final class CustomCatchModel {
    private static final float PIXEL = 1f / 16;
    private static final float ATLAS = 64;
    private static final Region SIDE = new Region(0, 0, 32, 16);
    private static final Region BACK = new Region(0, 16, 32, 4);
    private static final Region BELLY = new Region(0, 20, 32, 4);
    private static final Region MOUTH = new Region(36, 0, 8, 8);
    private static final Region END = new Region(46, 0, 8, 8);
    private static final Region TAIL_FIN = new Region(0, 28, 22, 18);
    private static final Region DORSAL_FIN = new Region(24, 28, 18, 18);
    private static final Region LOWER_FIN = new Region(44, 28, 18, 18);
    private static final Region FINLETS = new Region(0, 48, 22, 16);
    private static final Map<ResourceLocation, CustomCatchModel> MODELS = Map.of(
            CatchModelIds.LEECH, leech(),
            CatchModelIds.GOLDFISH, goldfish(),
            CatchModelIds.NEPTUNA, neptuna());

    private final List<Face> faces;
    private final List<Point> points;
    private final ResourceLocation texture;
    private final float size;
    private final float restRoll;

    private CustomCatchModel(String name, List<Face> faces, float restRoll) {
        this.faces = List.copyOf(faces);
        this.points = faces.stream().flatMap(face -> face.vertices.stream()).map(Vertex::point).distinct().toList();
        this.texture = new ResourceLocation("better_sunlit", "textures/fishing/" + name + ".png");
        float front = Float.NEGATIVE_INFINITY;
        float back = Float.POSITIVE_INFINITY;
        for (Point point : points) {
            front = Math.max(front, point.z);
            back = Math.min(back, point.z);
        }
        this.size = (front - back) * PIXEL;
        this.restRoll = (float) Math.toRadians(restRoll);
    }

    static CustomCatchModel get(ResourceLocation id) {
        return MODELS.get(id);
    }

    float size() {
        return size;
    }

    void draw(PoseStack pose, MultiBufferSource buffers, int light, float yaw, float settled) {
        float roll = restRoll * settled;
        float sin = (float) Math.sin(roll);
        float cos = (float) Math.cos(roll);
        float bottom = Float.POSITIVE_INFINITY;
        for (Point point : points) {
            bottom = Math.min(bottom, point.x * sin + point.y * cos);
        }
        // Rotate the fish onto its side without letting the lower fins sink into the ground.
        pose.m_252880_(0, -bottom * PIXEL + .012f, 0);
        pose.m_252781_(new Quaternionf().rotationY((float) Math.toRadians(-yaw)).rotateZ(roll));
        var consumer = buffers.m_6299_(RenderType.m_110452_(texture));
        var positionMatrix = pose.m_85850_().m_252922_();
        var normalMatrix = pose.m_85850_().m_252943_();
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        for (Face face : faces) {
            normalMatrix.transform(face.normal, normal).normalize();
            for (Vertex vertex : face.vertices) {
                Point point = vertex.point;
                positionMatrix.transformPosition(point.x * PIXEL, point.y * PIXEL, point.z * PIXEL, position);
                consumer.m_5954_(position.x, position.y, position.z, 1, 1, 1, 1, vertex.u, vertex.v,
                        OverlayTexture.f_118083_, light, normal.x, normal.y, normal.z);
            }
        }
    }

    private static CustomCatchModel leech() {
        var mesh = new Mesh(1, 1, 1);
        mesh.body(
                new Section(7.35f, -.50f, 1.33f, 1.14f, 42),
                new Section(6.88f, -.30f, 1.47f, 1.26f, 38),
                new Section(6.15f, .36f, 1.27f, 1.18f, 26),
                section(5.35f, .96f, 1.12f, 1.35f),
                section(4.45f, 1.90f, 1.18f, 1.46f),
                section(3.25f, 2.83f, 1.32f, 1.49f),
                section(1.84f, 3.37f, 1.39f, 1.43f),
                section(.32f, 3.59f, 1.43f, 1.36f),
                section(-1.03f, 3.38f, 1.32f, 1.39f),
                section(-2.36f, 2.85f, 1.25f, 1.47f),
                section(-3.50f, 1.80f, 1.16f, 1.32f),
                section(-4.40f, .74f, 1.03f, 1.12f),
                section(-5.15f, -.09f, 1.16f, .92f),
                section(-5.87f, -.52f, 1.39f, .75f),
                section(-6.74f, -.57f, 1.28f, .60f),
                section(-7.63f, -.46f, .91f, .40f),
                section(-8.55f, -.32f, .54f, .24f),
                section(-9.04f, -.29f, .26f, .15f));
        return new CustomCatchModel("leech", mesh.faces, -13);
    }

    private static CustomCatchModel goldfish() {
        var mesh = new Mesh(1.08f, 1.14f, .94f);
        mesh.body(
                section(5.62f, -.49f, .57f, .67f),
                section(5.17f, -.33f, 1.19f, 1.37f),
                section(4.46f, -.13f, 1.60f, 1.81f),
                section(3.36f, .03f, 1.91f, 2.22f),
                section(1.82f, .18f, 2.02f, 2.52f),
                section(.17f, .19f, 1.90f, 2.58f),
                section(-1.42f, .18f, 1.48f, 2.13f),
                section(-2.86f, .23f, .96f, 1.55f),
                section(-4.03f, .35f, .55f, .85f),
                section(-4.86f, .35f, .31f, .47f));
        mesh.fin(DORSAL_FIN, p(0, 2.24f, -.38f),
                p(0, 2.17f, 2.98f), p(0, 3.48f, 2.06f), p(0, 4.03f, 1.52f),
                p(0, 4.12f, .62f), p(0, 3.69f, .47f), p(0, 3.61f, -.59f),
                p(0, 3.10f, -1.29f), p(0, 1.75f, -2.70f));
        mesh.fin(LOWER_FIN, p(0, -1.46f, -1.86f),
                p(0, -1.74f, -.66f), p(0, -2.68f, -1.34f), p(0, -3.08f, -2.11f),
                p(0, -2.63f, -2.79f), p(0, -1.86f, -3.29f), p(0, -.58f, -4.28f));
        mesh.fin(TAIL_FIN, p(.15f, 1.79f, -6.67f),
                p(0, .65f, -4.48f), p(.24f, 2.14f, -5.79f), p(.39f, 3.38f, -6.89f),
                p(.43f, 3.50f, -8.07f), p(.38f, 2.93f, -8.83f),
                p(.25f, 1.80f, -8.36f), p(.07f, .38f, -7.40f), p(0, .23f, -4.52f));
        mesh.fin(TAIL_FIN, p(-.22f, .13f, -7.26f),
                p(0, .42f, -4.66f), p(-.22f, 1.28f, -7.30f), p(-.42f, 1.31f, -8.73f),
                p(-.48f, .80f, -9.45f), p(-.41f, .12f, -9.51f),
                p(-.17f, -.53f, -8.08f), p(0, -.06f, -4.63f));
        mesh.fin(TAIL_FIN, p(.18f, -.98f, -6.86f),
                p(0, .19f, -4.49f), p(.08f, .15f, -7.46f), p(.25f, -.67f, -8.70f),
                p(.43f, -1.59f, -8.91f), p(.47f, -2.49f, -8.23f),
                p(.28f, -2.31f, -7.35f), p(.11f, -1.52f, -6.40f), p(0, -.12f, -4.37f));
        for (int side : new int[]{-1, 1}) {
            mesh.fin(LOWER_FIN, p(side * 1.90f, -1.23f, 1.07f),
                    p(side * 1.63f, -.37f, 2.94f), p(side * 2.21f, -.85f, 1.99f),
                    p(side * 2.48f, -1.29f, .73f), p(side * 2.31f, -1.93f, -.60f),
                    p(side * 1.90f, -1.95f, -.64f), p(side * 1.65f, -1.53f, .16f));
        }
        return new CustomCatchModel("goldfish", mesh.faces, 74);
    }

    private static CustomCatchModel neptuna() {
        var mesh = new Mesh(1.09f, 1.15f, .89f);
        mesh.body(
                section(7.42f, -.59f, .69f, .65f),
                section(6.76f, -.37f, 1.20f, 1.14f),
                section(5.89f, -.12f, 1.68f, 1.56f),
                section(4.63f, .05f, 1.88f, 2.12f),
                section(2.93f, .08f, 1.82f, 2.28f),
                section(1.27f, .06f, 1.66f, 2.17f),
                section(-.51f, .01f, 1.45f, 1.93f),
                section(-2.05f, -.04f, 1.08f, 1.47f),
                section(-3.52f, -.09f, .75f, 1.03f),
                section(-4.81f, -.05f, .48f, .64f),
                section(-6.08f, .01f, .31f, .41f));
        mesh.fin(DORSAL_FIN, p(0, 2.01f, 1.65f),
                p(0, 1.64f, 4.98f), p(0, 3.07f, 3.65f), p(0, 4.01f, 2.56f),
                p(0, 3.48f, 1.98f), p(0, 3.12f, 2.02f), p(0, 3.27f, .64f),
                p(0, 2.90f, .05f), p(0, 2.59f, .14f), p(0, 2.51f, -.94f),
                p(0, 1.31f, -2.47f));
        mesh.fin(TAIL_FIN, p(0, 1.42f, -8.10f),
                p(0, .15f, -5.75f), p(0, 1.58f, -7.05f), p(.16f, 2.81f, -8.77f),
                p(.23f, 3.12f, -9.68f), p(.19f, 2.49f, -10.70f),
                p(.08f, 1.28f, -9.86f), p(0, .05f, -8.50f), p(0, -.19f, -5.77f));
        mesh.fin(TAIL_FIN, p(0, -1.15f, -7.91f),
                p(0, .13f, -5.77f), p(0, -.24f, -8.49f), p(-.12f, -.83f, -9.90f),
                p(-.22f, -1.31f, -10.30f), p(-.20f, -2.24f, -9.36f),
                p(-.10f, -2.26f, -8.42f), p(0, -1.60f, -7.44f), p(0, -.33f, -5.72f));
        mesh.fin(LOWER_FIN, p(0, -1.61f, -.78f),
                p(0, -1.68f, 1.47f), p(0, -2.47f, .16f), p(0, -3.02f, -1.22f),
                p(0, -2.60f, -2.30f), p(0, -2.05f, -1.95f), p(0, -1.10f, -3.62f));
        for (int index = 0; index < 2; index++) {
            float z = -3.07f - index * 1.31f;
            float y = .92f - index * .30f;
            mesh.fin(FINLETS, p(0, y, z - .48f), p(0, y - .24f, z + .27f),
                    p(0, y + .71f, z - .45f), p(0, y + .68f, z - .93f), p(0, y - .30f, z - 1.17f));
            mesh.fin(FINLETS, p(0, -y, z - .35f), p(0, -y + .25f, z + .42f),
                    p(0, -y - .67f, z - .58f), p(0, -y - .58f, z - 1.02f), p(0, -y + .31f, z - 1.18f));
        }
        for (int side : new int[]{-1, 1}) {
            mesh.fin(LOWER_FIN, p(side * 2.06f, -1.02f, 2.04f),
                    p(side * 1.58f, -.36f, 4.06f), p(side * 2.28f, -.79f, 2.43f),
                    p(side * 2.70f, -1.23f, .46f), p(side * 2.21f, -2.05f, -.84f),
                    p(side * 1.59f, -1.71f, .36f), p(side * 1.44f, -1.22f, 1.58f));
        }
        return new CustomCatchModel("neptuna", mesh.faces, 75);
    }

    private static Section section(float z, float y, float width, float height) {
        return new Section(z, y, width, height, 0);
    }

    private static Point p(float x, float y, float z) {
        return new Point(x, y, z);
    }

    private record Point(float x, float y, float z) {}

    private record Vertex(Point point, float u, float v) {}

    private record Face(List<Vertex> vertices, Vector3f normal) {}

    private record Region(int x, int y, int width, int height) {
        Vertex at(Point point, float u, float v) {
            return new Vertex(point, (x + .5f + u * (width - 1)) / ATLAS, (y + .5f + v * (height - 1)) / ATLAS);
        }
    }

    private record Section(float z, float y, float width, float height, float tilt) {
        private static final float[] X = {.55f, 1, 1, .55f, -.55f, -1, -1, -.55f};
        private static final float[] Y = {1, .57f, -.57f, -1, -1, -.57f, .57f, 1};

        Point point(int index) {
            double pitch = Math.toRadians(tilt);
            return p(X[index] * width, y + Y[index] * height * (float) Math.cos(pitch),
                    z + Y[index] * height * (float) Math.sin(pitch));
        }
    }

    private static final class Mesh {
        private final List<Face> faces = new ArrayList<>();
        private final float widthScale;
        private final float heightScale;
        private final float lengthScale;

        Mesh(float widthScale, float heightScale, float lengthScale) {
            this.widthScale = widthScale;
            this.heightScale = heightScale;
            this.lengthScale = lengthScale;
        }

        void body(Section... sections) {
            float start = sections[0].z;
            float end = sections[sections.length - 1].z;
            cap(sections[0], MOUTH, true);
            for (int i = 0; i < sections.length - 1; i++) {
                Section front = sections[i];
                Section back = sections[i + 1];
                float u0 = (start - front.z) / (start - end);
                float u1 = (start - back.z) / (start - end);
                for (int edge = 0; edge < 8; edge++) {
                    int next = (edge + 1) % 8;
                    Region texture = edge == 3 ? BELLY : edge == 7 ? BACK : SIDE;
                    float v0 = across(texture, edge);
                    float v1 = across(texture, next);
                    face(texture.at(front.point(edge), u0, v0), texture.at(front.point(next), u0, v1),
                            texture.at(back.point(next), u1, v1), texture.at(back.point(edge), u1, v0));
                }
            }
            cap(sections[sections.length - 1], END, false);
        }

        private static float across(Region region, int index) {
            if (region == SIDE) return (1 - Section.Y[index]) / 2;
            return Section.X[index] > 0 ? 1 : 0;
        }

        private void cap(Section section, Region texture, boolean front) {
            Vertex center = texture.at(p(0, section.y, section.z), .5f, .5f);
            for (int i = 0; i < 8; i++) {
                Vertex a = capVertex(section, texture, i);
                Vertex b = capVertex(section, texture, (i + 1) % 8);
                triangle(center, front ? b : a, front ? a : b);
            }
        }

        private static Vertex capVertex(Section section, Region texture, int index) {
            return texture.at(section.point(index), (1 + Section.X[index]) / 2, (1 - Section.Y[index]) / 2);
        }

        /** Fins are two sided. Their outline follows the individual lobes and rays rather than the atlas rectangle. */
        void fin(Region texture, Point inner, Point... outline) {
            float highZ = Float.NEGATIVE_INFINITY;
            float lowZ = Float.POSITIVE_INFINITY;
            float highY = Float.NEGATIVE_INFINITY;
            float lowY = Float.POSITIVE_INFINITY;
            for (Point point : outline) {
                highZ = Math.max(highZ, point.z);
                lowZ = Math.min(lowZ, point.z);
                highY = Math.max(highY, point.y);
                lowY = Math.min(lowY, point.y);
            }
            Vertex center = texture.at(inner, (highZ - inner.z) / (highZ - lowZ), (highY - inner.y) / (highY - lowY));
            Vertex[] rim = new Vertex[outline.length];
            for (int i = 0; i < outline.length; i++) {
                Point point = outline[i];
                rim[i] = texture.at(point, (highZ - point.z) / (highZ - lowZ), (highY - point.y) / (highY - lowY));
            }
            for (int i = 0; i < rim.length; i++) {
                Vertex a = rim[i];
                Vertex b = rim[(i + 1) % rim.length];
                triangle(center, a, b);
                triangle(center, b, a);
            }
        }

        private void triangle(Vertex a, Vertex b, Vertex c) {
            face(a, b, c, c);
        }

        private Vertex scale(Vertex vertex) {
            Point point = vertex.point;
            return new Vertex(p(point.x * widthScale, point.y * heightScale, point.z * lengthScale), vertex.u, vertex.v);
        }

        private void face(Vertex a, Vertex b, Vertex c, Vertex d) {
            a = scale(a);
            b = scale(b);
            c = scale(c);
            d = scale(d);
            Point origin = a.point;
            var normal = new Vector3f(b.point.x - origin.x, b.point.y - origin.y, b.point.z - origin.z)
                    .cross(c.point.x - origin.x, c.point.y - origin.y, c.point.z - origin.z);
            if (normal.lengthSquared() > .000001f) {
                faces.add(new Face(List.of(a, b, c, d), normal.normalize()));
            }
        }
    }
}
