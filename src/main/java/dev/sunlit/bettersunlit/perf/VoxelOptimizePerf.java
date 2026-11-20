package dev.sunlit.bettersunlit.perf;

import dev.sunlit.fishvisuals.mixin.PerfArrayVoxelShapeAccessor;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Replaces vanilla's {@code O(B^2)} box-by-box {@code Shapes.joinUnoptimized} loop inside
 * {@code VoxelShape.optimize()} ({@code m_83296_}) and {@code Shapes.or(first, others...)}
 * ({@code m_83124_}) with an {@code O(B log B)} single-pass coordinate compression and
 * contiguous {@link BitSet#set(int, int)} fill.
 */
public final class VoxelOptimizePerf {
    public interface OptMarker {
        boolean sunlitIsOptimized();

        void sunlitMarkOptimized();
    }

    private static final double EPS = 1.0E-7D;
    private static final int MAX_SINGLE_PASS_BOXES = 8192;
    private static final long MAX_GRID_CELLS = 1_000_000L;
    private static final ThreadLocal<BoxScratch> SCRATCH = ThreadLocal.withInitial(BoxScratch::new);

    private VoxelOptimizePerf() {
    }

    public static VoxelShape optimize(VoxelShape shape) {
        if (shape == null || shape.m_83281_()) {
            return Shapes.m_83040_();
        }
        if (((Object) shape) instanceof OptMarker marker && marker.sunlitIsOptimized()) {
            return shape;
        }
        BoxScratch s = acquireScratch();
        try {
            if (!collectBoxes(shape, s)) {
                return null;
            }
            return buildFromScratch(s);
        } finally {
            s.release();
        }
    }

    public static VoxelShape orTwo(VoxelShape first, VoxelShape second) {
        if (first == null || second == null) {
            return null;
        }
        if (first.m_83281_()) {
            return second;
        }
        if (second.m_83281_() || first == second) {
            return first;
        }
        BoxScratch s = SCRATCH.get();
        if (!s.inUse) {
            if (s.lastA == first && s.lastB == second && s.lastOut != null) {
                return s.lastOut;
            }
            s.reset();
            s.inUse = true;
            try {
                if (!collectBoxes(first, s) || !collectBoxes(second, s)) {
                    return null;
                }
                VoxelShape out = buildFromScratch(s);
                if (out != null) {
                    s.lastA = first;
                    s.lastB = second;
                    s.lastOut = out;
                }
                return out;
            } finally {
                s.release();
            }
        }
        BoxScratch fb = new BoxScratch();
        fb.inUse = true;
        fb.pooled = false;
        if (!collectBoxes(first, fb) || !collectBoxes(second, fb)) {
            return null;
        }
        return buildFromScratch(fb);
    }

    public static VoxelShape orAll(VoxelShape first, VoxelShape[] others) {
        if (first == null) {
            return null;
        }
        if (others == null || others.length == 0) {
            return first;
        }
        boolean anyNonEmpty = !first.m_83281_();
        if (!anyNonEmpty) {
            for (int i = 0; i < others.length; i++) {
                VoxelShape v = others[i];
                if (v != null && !v.m_83281_()) {
                    anyNonEmpty = true;
                    break;
                }
            }
            if (!anyNonEmpty) {
                return Shapes.m_83040_();
            }
        }
        BoxScratch s = acquireScratch();
        try {
            if (!first.m_83281_() && !collectBoxes(first, s)) {
                return null;
            }
            for (int i = 0; i < others.length; i++) {
                VoxelShape v = others[i];
                if (v != null && !v.m_83281_() && !collectBoxes(v, s)) {
                    return null;
                }
            }
            return buildFromScratch(s);
        } finally {
            s.release();
        }
    }

    private static BoxScratch acquireScratch() {
        BoxScratch tl = SCRATCH.get();
        if (!tl.inUse) {
            tl.reset();
            tl.inUse = true;
            return tl;
        }
        BoxScratch fallback = new BoxScratch();
        fallback.inUse = true;
        fallback.pooled = false;
        return fallback;
    }

    private static boolean collectBoxes(VoxelShape shape, BoxScratch s) {
        shape.m_83286_(s.consumer);
        return !s.overflow;
    }

    private static VoxelShape buildFromScratch(BoxScratch s) {
        int count = s.boxCount;
        if (count == 0) {
            return Shapes.m_83040_();
        }
        double[] b = s.boxes;
        if (count == 1) {
            return Shapes.m_166049_(b[0], b[1], b[2], b[3], b[4], b[5]);
        }

        int rawLen = count * 2;
        s.ensureAxisCapacity(rawLen);
        double[] rawX = s.rawX;
        double[] rawY = s.rawY;
        double[] rawZ = s.rawZ;
        for (int i = 0, p = 0; i < count; i++, p += 6) {
            int j = i << 1;
            rawX[j] = b[p];
            rawX[j + 1] = b[p + 3];
            rawY[j] = b[p + 1];
            rawY[j + 1] = b[p + 4];
            rawZ[j] = b[p + 2];
            rawZ[j + 1] = b[p + 5];
        }

        Arrays.sort(rawX, 0, rawLen);
        Arrays.sort(rawY, 0, rawLen);
        Arrays.sort(rawZ, 0, rawLen);

        int ux = dedupAxis(rawX, rawLen, s.uniqX);
        int uy = dedupAxis(rawY, rawLen, s.uniqY);
        int uz = dedupAxis(rawZ, rawLen, s.uniqZ);
        int nx = ux - 1;
        int ny = uy - 1;
        int nz = uz - 1;
        if (nx <= 0 || ny <= 0 || nz <= 0) {
            return Shapes.m_83040_();
        }
        if ((long) nx * (long) ny * (long) nz > MAX_GRID_CELLS) {
            return null;
        }

        BitSetDiscreteVoxelShape discrete = new BitSetDiscreteVoxelShape(nx, ny, nz);
        if (!(((Object) discrete) instanceof VoxelJoinPerf.BitSetShapeTarget target)) {
            return null;
        }
        BitSet bits = target.sunlitGetStorage();
        double[] xs = s.uniqX;
        double[] ys = s.uniqY;
        double[] zs = s.uniqZ;

        int minX = nx;
        int minY = ny;
        int minZ = nz;
        int maxX = 0;
        int maxY = 0;
        int maxZ = 0;
        for (int i = 0, p = 0; i < count; i++, p += 6) {
            int ix0 = findAxisIndex(xs, ux, b[p]);
            int ix1 = findAxisIndex(xs, ux, b[p + 3]);
            int iy0 = findAxisIndex(ys, uy, b[p + 1]);
            int iy1 = findAxisIndex(ys, uy, b[p + 4]);
            int iz0 = findAxisIndex(zs, uz, b[p + 2]);
            int iz1 = findAxisIndex(zs, uz, b[p + 5]);
            if (ix1 <= ix0 || iy1 <= iy0 || iz1 <= iz0) {
                continue;
            }
            if (ix0 < minX) minX = ix0;
            if (ix1 > maxX) maxX = ix1;
            if (iy0 < minY) minY = iy0;
            if (iy1 > maxY) maxY = iy1;
            if (iz0 < minZ) minZ = iz0;
            if (iz1 > maxZ) maxZ = iz1;

            for (int x = ix0; x < ix1; x++) {
                int xOff = x * ny;
                for (int y = iy0; y < iy1; y++) {
                    int base = (xOff + y) * nz;
                    bits.set(base + iz0, base + iz1);
                }
            }
        }

        if (minX >= maxX || minY >= maxY || minZ >= maxZ) {
            return Shapes.m_83040_();
        }
        target.sunlitSetBounds(minX, minY, minZ, maxX, maxY, maxZ);

        VoxelShape built = PerfArrayVoxelShapeAccessor.sunlitCreate(
                discrete,
                DoubleArrayList.wrap(Arrays.copyOf(xs, ux)),
                DoubleArrayList.wrap(Arrays.copyOf(ys, uy)),
                DoubleArrayList.wrap(Arrays.copyOf(zs, uz)));
        if (((Object) built) instanceof OptMarker marker) {
            marker.sunlitMarkOptimized();
        }
        return built;
    }

    private static int dedupAxis(double[] sorted, int len, double[] out) {
        double prev = sorted[0];
        out[0] = prev;
        int u = 1;
        for (int i = 1; i < len; i++) {
            double v = sorted[i];
            if (v - prev > EPS) {
                out[u++] = v;
                prev = v;
            }
        }
        return u;
    }

    private static int findAxisIndex(double[] uniq, int len, double value) {
        int idx = Arrays.binarySearch(uniq, 0, len, value);
        if (idx >= 0) {
            return idx;
        }
        int ip = -idx - 1;
        if (ip == 0) {
            return 0;
        }
        if (ip >= len) {
            return len - 1;
        }
        // Raw point was absorbed into uniq[ip - 1] during dedup if value - uniq[ip - 1] <= EPS.
        if (value - uniq[ip - 1] <= EPS) {
            return ip - 1;
        }
        return ip;
    }

    private static final class BoxScratch {
        double[] boxes = new double[6 * 64];
        double[] rawX = new double[128];
        double[] rawY = new double[128];
        double[] rawZ = new double[128];
        double[] uniqX = new double[128];
        double[] uniqY = new double[128];
        double[] uniqZ = new double[128];
        int boxCount;
        boolean overflow;
        boolean inUse;
        boolean pooled = true;
        VoxelShape lastA;
        VoxelShape lastB;
        VoxelShape lastOut;

        final Shapes.DoubleLineConsumer consumer = (x0, y0, z0, x1, y1, z1) -> {
            if (overflow) {
                return;
            }
            if (!(x1 - x0 >= EPS && y1 - y0 >= EPS && z1 - z0 >= EPS)) {
                return;
            }
            if (boxCount >= MAX_SINGLE_PASS_BOXES) {
                overflow = true;
                return;
            }
            int p = boxCount * 6;
            if (p + 6 > boxes.length) {
                boxes = Arrays.copyOf(boxes, boxes.length << 1);
            }
            boxes[p] = x0;
            boxes[p + 1] = y0;
            boxes[p + 2] = z0;
            boxes[p + 3] = x1;
            boxes[p + 4] = y1;
            boxes[p + 5] = z1;
            boxCount++;
        };

        void reset() {
            boxCount = 0;
            overflow = false;
        }

        void release() {
            if (pooled) {
                inUse = false;
                boxCount = 0;
                overflow = false;
            }
        }

        void ensureAxisCapacity(int n) {
            if (rawX.length < n) {
                int cap = Math.max(n, rawX.length << 1);
                rawX = new double[cap];
                rawY = new double[cap];
                rawZ = new double[cap];
                uniqX = new double[cap];
                uniqY = new double[cap];
                uniqZ = new double[cap];
            }
        }
    }
}
