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
    // For the leech, each relaxed vertex corresponds to the same vertex in the contracted mesh.
    private final List<Face> relaxedFaces;
    private final List<Point> points;
    private final ResourceLocation texture;
    private final float size;
    private final float restRoll;

    private CustomCatchModel(String name, List<Face> faces, float restRoll) {
        this(name, faces, restRoll, List.of());
    }

    private CustomCatchModel(String name, List<Face> faces, float restRoll, List<Face> relaxedFaces) {
        this.faces = List.copyOf(faces);
        this.relaxedFaces = List.copyOf(relaxedFaces);
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
        draw(pose, buffers, light, yaw, settled, 1);
    }

    void drawLeech(PoseStack pose, MultiBufferSource buffers, int light, float yaw,
                   double age, double elapsed, double sinceKick, double remaining, float strength) {
        // Hold a loose curve on the line. After landing, keep the rear sucker planted
        // as the front draws back, the middle arches, and the body lengthens again.
        float airCurl = .28f + .12f * (float) Math.cos(Math.min(age, 12) * Math.PI / 12);
        float curl = airCurl;
        if (elapsed >= 0) {
            double settle = Math.min(1, elapsed / 5);
            double phase = Math.max(0, Math.min(1, sinceKick / 16));
            double wave = Math.sin(Math.PI * phase);
            curl = (float) (airCurl * (1 - settle) + wave * wave * strength * Math.max(0, Math.min(1, remaining / 6)) * settle);
        }
        draw(pose, buffers, light, yaw, 0, curl);
    }

    private void draw(PoseStack pose, MultiBufferSource buffers, int light, float yaw, float settled, float curl) {
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
        for (int i = 0; i < faces.size(); i++) {
            Face face = faces.get(i);
            Face relaxed = curl < 1 && !relaxedFaces.isEmpty() ? relaxedFaces.get(i) : null;
            normal.set(face.normal);
            if (relaxed != null) normal.lerp(relaxed.normal, 1 - curl);
            normalMatrix.transform(normal).normalize();
            for (int j = 0; j < face.vertices.size(); j++) {
                Vertex vertex = face.vertices.get(j);
                Point point = vertex.point;
                position.set(point.x, point.y, point.z);
                if (relaxed != null) {
                    Point start = relaxed.vertices.get(j).point;
                    position.set(start.x + curl * (point.x - start.x), start.y + curl * (point.y - start.y),
                            start.z + curl * (point.z - start.z));
                }
                positionMatrix.transformPosition(position.mul(PIXEL));
                consumer.m_5954_(position.x, position.y, position.z, 1, 1, 1, 1, vertex.u, vertex.v,
                        OverlayTexture.f_118083_, light, normal.x, normal.y, normal.z);
            }
        }
    }

    private static CustomCatchModel leech() {
        var mesh = new Mesh();
        Section[] sections = {
                new Section(7.35f, -.35f, 1.23f, .82f, 38),
                new Section(6.72f, -.11f, 1.35f, .94f, 25),
                section(5.72f, .64f, 1.08f, .84f),
                section(4.20f, 1.86f, 1.13f, .99f),
                section(2.15f, 2.89f, 1.24f, 1.04f),
                section(.20f, 3.05f, 1.30f, 1.03f),
                section(-1.65f, 2.65f, 1.26f, 1.00f),
                section(-3.35f, 1.44f, 1.18f, .91f),
                section(-4.97f, .09f, 1.09f, .71f),
                section(-6.50f, -.60f, 1.27f, .53f),
                section(-7.70f, -.79f, .89f, .34f),
                section(-9.04f, -.88f, .45f, .20f)};
        mesh.body(sections);
        // Build both poses from the same rings so the sucker and every textured face
        // keep their correspondence. The resting belly follows the contact plane.
        float floor = Float.POSITIVE_INFINITY;
        for (Section ring : sections) floor = Math.min(floor, ring.point(3).y);
        Section[] extended = new Section[sections.length];
        float rear = sections[sections.length - 1].z;
        for (int i = 0; i < sections.length; i++) {
            Section ring = sections[i];
            float t = (ring.z - rear) / (sections[0].z - rear);
            float arch = (float) Math.sin(Math.PI * t);
            float tilt = ring.tilt * .45f;
            float depth = ring.height * (float) Math.cos(Math.toRadians(tilt));
            extended[i] = new Section(ring.z + 2.9f * t * t, floor + depth + .15f * arch,
                    ring.width, ring.height, tilt, .85f * arch);
        }
        var relaxed = new Mesh();
        relaxed.body(extended);
        return new CustomCatchModel("leech", mesh.faces, -13, relaxed.faces);
    }

    private static CustomCatchModel goldfish() {
        var mesh = new Mesh();
        mesh.fishBody(new Rim(5.9f, .87f, -1.48f, 1.27f), new Rim(3.15f, 1.08f, -1.84f, 1.65f),
                new Rim(-2.25f, 1.02f, -1.74f, 1.75f), new Rim(-4.12f, .63f, -1.00f, .98f),
                new Rim(-5.43f, .43f, -.57f, .62f));
        mesh.centerFin(DORSAL_FIN, 2.9f, -2.95f, 1.42f, 3.22f);
        mesh.centerFin(ANAL_FIN, .75f, -4.0f, -3.0f, -1.35f);
        mesh.centerFin(TAIL_FIN, -4.9f, -9.85f, -2.7f, 2.90f);
        mesh.pairedFins(.97f, 1.95f, -.42f, -1.28f, 2.85f, -.50f, .76f);
        return new CustomCatchModel("goldfish", mesh.faces, 82);
    }

    private static CustomCatchModel neptuna() {
        var mesh = new Mesh();
        mesh.fishBody(new Rim(6.65f, .84f, -1.27f, 1.03f), new Rim(3.75f, 1.06f, -1.53f, 1.59f),
                new Rim(-2.55f, 1.02f, -1.45f, 1.67f), new Rim(-4.84f, .60f, -.84f, .94f),
                new Rim(-6.30f, .36f, -.47f, .53f));
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

    private record Rim(float z, float halfWidth, float low, float high) {
        Point corner(int side, boolean upper) {
            return p(side * halfWidth, upper ? high : low, z);
        }
    }

    private record Section(float z, float y, float width, float height, float tilt, float lateral) {
        Section(float z, float y, float width, float height, float tilt) {
            this(z, y, width, height, tilt, 0);
        }

        private static final float[] X = {.55f, 1, 1, .55f, -.55f, -1, -1, -.55f};
        private static final float[] Y = {1, .57f, -.57f, -1, -1, -.57f, .57f, 1};

        Point point(int index) {
            double pitch = Math.toRadians(tilt);
            return p(lateral + X[index] * width, y + Y[index] * height * (float) Math.cos(pitch),
                    z + Y[index] * height * (float) Math.sin(pitch));
        }
    }

    private static final class Mesh {
        private final List<Face> faces = new ArrayList<>();
        /** One atlas row per body part, ordered side, back, belly, front and rear. */
        void fishBody(Rim... rims) {
            for (int i = 1; i < rims.length; i++) {
                int row = (i - 1) * 10;
                Region side = new Region(0, row, 16, 10);
                Rim front = rims[i - 1], rear = rims[i];
                Point frontLeftTop = front.corner(-1, true), frontRightTop = front.corner(1, true);
                Point frontLeftBottom = front.corner(-1, false), frontRightBottom = front.corner(1, false);
                Point rearLeftTop = rear.corner(-1, true), rearRightTop = rear.corner(1, true);
                Point rearLeftBottom = rear.corner(-1, false), rearRightBottom = rear.corner(1, false);
                quad(side, frontRightTop, frontRightBottom, rearRightBottom, rearRightTop);
                face(side.at(rearLeftTop, 1, 0), side.at(rearLeftBottom, 1, 1), side.at(frontLeftBottom, 0, 1), side.at(frontLeftTop, 0, 0));
                quad(new Region(16, row, 16, 10), frontLeftTop, frontRightTop, rearRightTop, rearLeftTop);
                quad(new Region(32, row, 16, 10), frontRightBottom, frontLeftBottom, rearLeftBottom, rearRightBottom);
                if (i == 1) quad(new Region(48, row, 8, 10), frontLeftTop, frontLeftBottom, frontRightBottom, frontRightTop);
                if (i == rims.length - 1) quad(new Region(56, row, 8, 10), rearRightTop, rearRightBottom, rearLeftBottom, rearLeftTop);
            }
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
