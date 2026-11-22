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
    private static final Region TAIL_FIN = new Region(0, 28, 10, 11);
    private static final Region PECTORAL_FIN = new Region(40, 28, 6, 5);
    private static final Map<ResourceLocation, CustomCatchModel> MODELS = Map.of(
            CatchModelIds.LEECH, leech(),
            CatchModelIds.GOLDFISH, goldfish(),
            CatchModelIds.NEPTUNA, neptuna());

    private final List<Face> faces;
    private final List<Section> leechSections;
    private final List<Point> points;
    private final ResourceLocation texture;
    private final float size;
    private final float restRoll;

    private CustomCatchModel(String name, List<Face> faces, float restRoll) {
        this(name, faces, restRoll, List.of());
    }

    private CustomCatchModel(String name, List<Face> faces, float restRoll, List<Section> leechSections) {
        this.faces = List.copyOf(faces);
        this.leechSections = List.copyOf(leechSections);
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
        draw(pose, buffers, light, yaw, settled, null);
    }

    void drawLeech(PoseStack pose, MultiBufferSource buffers, int light, float yaw,
                   double age, double elapsed, double sinceKick, double remaining, float strength) {
        // A low travelling bend passes from the sucker to the tail. The belly stays
        // level, and the whole ribbon gradually settles into its resting S shape.
        double time = elapsed >= 0 ? elapsed : age;
        double pulse = Math.sin(Math.PI * Math.min(1, Math.max(0, sinceKick / 16)));
        double amplitude = elapsed < 0 ? .75 : Math.min(1, Math.max(0, remaining / 8))
                * strength * (.28 + .72 * pulse * pulse);
        Wave[] wave = new Wave[leechSections.size()];
        for (int i = 0; i < wave.length; i++) {
            Section section = leechSections.get(i);
            double phase = time * .29 + section.z * .55;
            wave[i] = new Wave((float) (1.05 * amplitude * Math.sin(phase)),
                    (float) (.5775 * amplitude * Math.cos(phase)));
        }
        draw(pose, buffers, light, yaw, 0, wave);
    }

    private record Wave(float offset, float slope) {}

    private void draw(PoseStack pose, MultiBufferSource buffers, int light, float yaw, float settled, Wave[] wave) {
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
                position.set(point.x, point.y, point.z);
                if (wave != null) {
                    Wave bend = wave[point.ring];
                    position.x += bend.offset;
                    normal.set(face.normal);
                    normal.z -= bend.slope * normal.x;
                    normalMatrix.transform(normal).normalize();
                }
                positionMatrix.transformPosition(position.mul(PIXEL));
                consumer.m_5954_(position.x, position.y, position.z, 1, 1, 1, 1, vertex.u, vertex.v,
                        OverlayTexture.f_118083_, light, normal.x, normal.y, normal.z);
            }
        }
    }

    private static CustomCatchModel leech() {
        var mesh = new Mesh();
        List<Section> sections = List.of(
                new Section(5.4f, .33f, .69f, .32f, -.55f),
                new Section(4.6f, .38f, .82f, .37f, -.98f),
                new Section(3.4f, .36f, .68f, .35f, -1.19f),
                new Section(2.2f, .43f, .89f, .41f, -.96f),
                new Section(.9f, .47f, .98f, .46f, -.48f),
                new Section(-.4f, .47f, 1.01f, .45f, .18f),
                new Section(-1.7f, .42f, .94f, .41f, .73f),
                new Section(-3.0f, .36f, .81f, .35f, .98f),
                new Section(-4.15f, .31f, .72f, .30f, .88f),
                new Section(-5.15f, .25f, .86f, .24f, .57f),
                new Section(-5.85f, .18f, .58f, .17f, .36f));
        mesh.body(sections);
        return new CustomCatchModel("leech", mesh.faces, 4, sections);
    }

    private static CustomCatchModel goldfish() {
        var mesh = new Mesh();
        // Short stepped cheek and deep flank, with a broad, rayed fan tail.
        mesh.profile(4.8f, 2.05f, .54f, .86f,
                2, 8, 1, 10, 0, 11, 0, 12, 0, 12, 0, 11, 1, 10, 2, 8);
        mesh.centerFin(new Region(20, 28, 9, 4), 3.55f, -.4f, 1.37f, 3.25f);
        mesh.centerFin(new Region(20, 43, 8, 5), 1.5f, -1.45f, -2.58f, -1.02f);
        mesh.centerFin(TAIL_FIN, -1.38f, -5.03f, -1.94f, 2.30f);
        mesh.pairedFins(PECTORAL_FIN, .81f, 1.65f, .02f, -.83f, 2.72f, .52f, .45f);
        return new CustomCatchModel("goldfish", mesh.faces, 80);
    }

    private static CustomCatchModel neptuna() {
        var mesh = new Mesh();
        // A single long ink-green flank carries the eye and broken turquoise flashes.
        mesh.profile(10.2f, 3.72f, .82f, 1.49f,
                2, 13, 1, 15, 0, 17, 0, 19, 0, 20, 0, 20, 0, 18, 0, 16, 1, 14, 2, 12);
        mesh.centerFin(new Region(20, 28, 17, 7), 7.3f, -3.8f, 2.63f, 5.6f);
        mesh.centerFin(new Region(20, 43, 12, 5), 3.9f, -3.7f, -4.93f, -2.25f);
        mesh.sheet(new Region(40, 45, 14, 3), p(0, 3.52f, -1.65f), p(0, 2.46f, -1.65f),
                p(0, .54f, -5.31f), p(0, 1.57f, -5.31f));
        mesh.centerFin(new Region(0, 28, 14, 14), -5.73f, -12.2f, -3.39f, 4.25f);
        mesh.pairedFins(new Region(40, 28, 10, 6), 1.45f, 3.0f, -.08f, -1.29f, 5.70f, .75f, 1.18f);
        return new CustomCatchModel("neptuna", mesh.faces, 83);
    }

    private static Point p(float x, float y, float z) {
        return new Point(x, y, z, -1);
    }

    private record Point(float x, float y, float z, int ring) {}

    private record Vertex(Point point, float u, float v) {}

    private record Face(List<Vertex> vertices, Vector3f normal) {}

    private record Region(int x, int y, int width, int height) {
        Vertex at(Point point, float u, float v) {
            return new Vertex(point, (x + .005f + u * (width - .01f)) / ATLAS, (y + .005f + v * (height - .01f)) / ATLAS);
        }
    }

    private record Section(float z, float y, float width, float height, float lateral) {
        private static final float[] X = {.56f, 1, 1, .56f, -.56f, -1, -1, -.56f};
        private static final float[] Y = {1, .45f, -.45f, -1, -1, -.45f, .45f, 1};

        Point point(int edge, int ring) {
            return new Point(lateral + X[edge] * width, y + Y[edge] * height, z, ring);
        }
    }

    private static final class Mesh {
        private final List<Face> faces = new ArrayList<>();
        // The side is a single flat slab. Trace its bevelled silhouette down the
        // painted rows; neighbouring rows meet at exactly the same corners.
        void profile(float nose, float crown, float pixel, float width, int... outline) {
            for (int row = 0; row < outline.length / 2 - 1; row++) {
                int first = 2 * row;
                float high = crown - row * pixel, low = high - pixel;
                float frontHigh = nose - outline[first] * pixel, rearHigh = nose - outline[first + 1] * pixel;
                float frontLow = nose - outline[first + 2] * pixel, rearLow = nose - outline[first + 3] * pixel;
                Point topLeft = p(-width, high, frontHigh), topRight = p(width, high, frontHigh);
                Point lowLeft = p(-width, low, frontLow), lowRight = p(width, low, frontLow);
                Point backTopLeft = p(-width, high, rearHigh), backTopRight = p(width, high, rearHigh);
                Point backLowLeft = p(-width, low, rearLow), backLowRight = p(width, low, rearLow);
                face(flank(topRight, outline[first], row), flank(lowRight, outline[first + 2], row + 1),
                        flank(backLowRight, outline[first + 3], row + 1), flank(backTopRight, outline[first + 1], row));
                face(flank(backTopLeft, outline[first + 1], row), flank(backLowLeft, outline[first + 3], row + 1),
                        flank(lowLeft, outline[first + 2], row + 1), flank(topLeft, outline[first], row));
                quad(new Region(32, row, 4, 1), topLeft, lowLeft, lowRight, topRight);
                quad(new Region(38, row, 4, 1), backTopRight, backLowRight, backLowLeft, backTopLeft);
                if (row == 0) quad(new Region(outline[first], 12, outline[first + 1] - outline[first], 4),
                        topLeft, topRight, backTopRight, backTopLeft);
                if (first + 4 == outline.length) quad(new Region(outline[first + 2], 17,
                        outline[first + 3] - outline[first + 2], 4), lowRight, lowLeft, backLowLeft, backLowRight);
            }
        }

        private Vertex flank(Point point, int column, int row) {
            return new Vertex(point, column / ATLAS, row / ATLAS);
        }

        void centerFin(Region texture, float front, float back, float bottom, float top) {
            sheet(texture, p(0, top, front), p(0, bottom, front), p(0, bottom, back), p(0, top, back));
        }

        void pairedFins(Region texture, float inner, float outer, float rootHeight, float tipHeight,
                        float front, float back, float sweep) {
            for (int side : new int[]{-1, 1}) {
                sheet(texture, p(side * inner, rootHeight, front),
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

        void body(List<Section> sections) {
            float start = sections.get(0).z;
            float end = sections.get(sections.size() - 1).z;
            cap(sections.get(0), MOUTH, true, 0);
            for (int i = 0; i < sections.size() - 1; i++) {
                Section front = sections.get(i);
                Section back = sections.get(i + 1);
                float u0 = (start - front.z) / (start - end);
                float u1 = (start - back.z) / (start - end);
                for (int edge = 0; edge < 8; edge++) {
                    int next = (edge + 1) % 8;
                    Region texture = edge == 3 ? BELLY : edge == 7 ? BACK : SIDE;
                    float v0 = across(texture, edge);
                    float v1 = across(texture, next);
                    face(texture.at(front.point(edge, i), u0, v0), texture.at(front.point(next, i), u0, v1),
                            texture.at(back.point(next, i + 1), u1, v1), texture.at(back.point(edge, i + 1), u1, v0));
                }
            }
            cap(sections.get(sections.size() - 1), END, false, sections.size() - 1);
        }

        private static float across(Region region, int index) {
            if (region == SIDE) return (1 - Section.Y[index]) / 2;
            return Section.X[index] > 0 ? 1 : 0;
        }

        private void cap(Section section, Region texture, boolean front, int ring) {
            Vertex center = texture.at(new Point(section.lateral, section.y, section.z, ring), .5f, .5f);
            for (int i = 0; i < 8; i++) {
                Vertex a = capVertex(section, texture, i, ring);
                Vertex b = capVertex(section, texture, (i + 1) % 8, ring);
                triangle(center, front ? b : a, front ? a : b);
            }
        }

        private static Vertex capVertex(Section section, Region texture, int index, int ring) {
            return texture.at(section.point(index, ring), (1 + Section.X[index]) / 2, (1 - Section.Y[index]) / 2);
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
