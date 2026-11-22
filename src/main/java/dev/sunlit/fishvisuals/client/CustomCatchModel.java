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
    private static final Region TAIL_FIN = new Region(0, 40, 20, 24);
    private static final Region DORSAL_FIN = new Region(20, 40, 20, 12);
    private static final Region PECTORAL_FIN = new Region(40, 40, 15, 12);
    private static final Region ANAL_FIN = new Region(20, 52, 20, 12);
    private static final Region FINLETS = new Region(40, 52, 24, 12);
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
        var mesh = new Mesh();
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
        var mesh = new Mesh();
        mesh.cuboid(0, .87f, -1.60f, 1.35f, 5.9f, 3.15f);
        mesh.cuboid(10, 1.12f, -1.95f, 1.81f, 3.15f, -2.25f);
        mesh.cuboid(20, .78f, -1.30f, 1.23f, -2.25f, -4.12f);
        mesh.cuboid(30, .48f, -.66f, .69f, -4.12f, -5.43f);
        mesh.centerFin(DORSAL_FIN, 2.9f, -2.95f, 1.42f, 3.22f);
        mesh.centerFin(ANAL_FIN, .75f, -4.0f, -3.0f, -1.35f);
        mesh.centerFin(TAIL_FIN, -4.9f, -9.85f, -2.7f, 2.90f);
        mesh.pairedFins(.97f, 1.95f, -.42f, -1.28f, 2.85f, -.50f, .76f);
        return new CustomCatchModel("goldfish", mesh.faces, 82);
    }

    private static CustomCatchModel neptuna() {
        var mesh = new Mesh();
        mesh.cuboid(0, .89f, -1.33f, 1.12f, 6.65f, 3.75f);
        mesh.cuboid(10, 1.10f, -1.56f, 1.74f, 3.75f, -2.55f);
        mesh.cuboid(20, .70f, -.98f, 1.09f, -2.55f, -4.84f);
        mesh.cuboid(30, .38f, -.53f, .58f, -4.84f, -6.30f);
        mesh.centerFin(DORSAL_FIN, 3.64f, -2.43f, 1.55f, 3.55f);
        mesh.centerFin(ANAL_FIN, 1.84f, -3.55f, -2.66f, -1.05f);
        mesh.centerFin(FINLETS, -2.14f, -6.11f, .51f, 1.75f);
        mesh.centerFin(FINLETS, -2.14f, -6.11f, -.39f, -1.65f);
        mesh.centerFin(TAIL_FIN, -5.87f, -10.73f, -2.77f, 3.23f);
        mesh.pairedFins(.94f, 1.88f, -.31f, -.95f, 3.11f, -.58f, 1.12f);
        return new CustomCatchModel("neptuna", mesh.faces, 84);
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
        /** One atlas row per body part, ordered side, back, belly, front and rear. */
        void cuboid(int row, float halfWidth, float low, float high, float front, float back) {
            Region side = new Region(0, row, 16, 10);
            Region top = new Region(16, row, 16, 10);
            Region bottom = new Region(32, row, 16, 10);
            Region nose = new Region(48, row, 8, 10);
            Region rear = new Region(56, row, 8, 10);
            Point frontLeftTop = p(-halfWidth, high, front);
            Point frontRightTop = p(halfWidth, high, front);
            Point backLeftTop = p(-halfWidth, high, back);
            Point backRightTop = p(halfWidth, high, back);
            Point frontLeftBottom = p(-halfWidth, low, front);
            Point frontRightBottom = p(halfWidth, low, front);
            Point backLeftBottom = p(-halfWidth, low, back);
            Point backRightBottom = p(halfWidth, low, back);
            quad(side, frontRightTop, frontRightBottom, backRightBottom, backRightTop);
            face(side.at(backLeftTop, 1, 0), side.at(backLeftBottom, 1, 1),
                    side.at(frontLeftBottom, 0, 1), side.at(frontLeftTop, 0, 0));
            quad(top, frontLeftTop, frontRightTop, backRightTop, backLeftTop);
            quad(bottom, frontRightBottom, frontLeftBottom, backLeftBottom, backRightBottom);
            quad(nose, frontLeftTop, frontLeftBottom, frontRightBottom, frontRightTop);
            quad(rear, backRightTop, backRightBottom, backLeftBottom, backLeftTop);
        }

        void centerFin(Region texture, float front, float back, float bottom, float top) {
            sheet(texture, p(0, top, front), p(0, bottom, front), p(0, bottom, back), p(0, top, back));
        }

        void pairedFins(float inner, float outer, float rootHeight, float tipHeight,
                        float front, float back, float sweep) {
            for (int side : new int[]{-1, 1}) {
                sheet(PECTORAL_FIN, p(side * inner, rootHeight, front),
                        p(side * outer, tipHeight, front - sweep),
                        p(side * outer, tipHeight, back - sweep), p(side * inner, rootHeight, back));
            }
        }

        // The alpha cutout defines the stepped edges and separated tips on both sides of the fin.
        private void sheet(Region texture, Point topLeft, Point bottomLeft, Point bottomRight, Point topRight) {
            Vertex a = texture.at(topLeft, 0, 0);
            Vertex b = texture.at(bottomLeft, 0, 1);
            Vertex c = texture.at(bottomRight, 1, 1);
            Vertex d = texture.at(topRight, 1, 0);
            face(a, b, c, d);
            face(d, c, b, a);
        }

        private void quad(Region texture, Point topLeft, Point bottomLeft, Point bottomRight, Point topRight) {
            face(texture.at(topLeft, 0, 0), texture.at(bottomLeft, 0, 1),
                    texture.at(bottomRight, 1, 1), texture.at(topRight, 1, 0));
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

        private void triangle(Vertex a, Vertex b, Vertex c) {
            face(a, b, c, c);
        }

        private void face(Vertex a, Vertex b, Vertex c, Vertex d) {
            Point origin = a.point;
            var normal = new Vector3f(b.point.x - origin.x, b.point.y - origin.y, b.point.z - origin.z)
                    .cross(c.point.x - origin.x, c.point.y - origin.y, c.point.z - origin.z);
            if (normal.lengthSquared() > .000001f) {
                faces.add(new Face(List.of(a, b, c, d), normal.normalize()));
            }
        }
    }
}
