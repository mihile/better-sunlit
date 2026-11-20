package dev.sunlit.bettersunlit.perf;

import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.BitSet;

/**
 * Allocation-free flattened replacement for {@code BitSetDiscreteVoxelShape.join} that collects each
 * 1D axis {@code IndexMerger} once via a package-local hidden {@code IndexConsumer} instead of
 * allocating {@code boolean[1]} and invoking nested lambdas per {@code (x, y)} slice.
 */
public final class VoxelJoinPerf {
    public interface BitSetShapeTarget {
        BitSet sunlitGetStorage();

        int sunlitGetXSize();

        int sunlitGetYSize();

        int sunlitGetZSize();

        void sunlitSetBounds(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax);
    }

    private static final class AxisScratch {
        final int[][][] axis = new int[3][3][];

        AxisScratch() {
            for (int a = 0; a < 3; a++) {
                axis[a][0] = new int[32];
                axis[a][1] = new int[32];
                axis[a][2] = new int[32];
            }
        }

        int collect(int axisIdx, Object merger, int minCap) {
            int[][] bufs = axis[axisIdx];
            int curLen = bufs[0].length;
            if (curLen < minCap) {
                int cap = Integer.highestOneBit(Math.max(32, minCap) - 1) << 1;
                bufs[0] = new int[cap];
                bufs[1] = new int[cap];
                bufs[2] = new int[cap];
            }
            while (true) {
                try {
                    int len = (int) MERGER_COLLECT_MH.invokeExact(merger, bufs[0], bufs[1], bufs[2]);
                    if (len >= 0) {
                        return len;
                    }
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
                int nextCap = bufs[0].length << 1;
                bufs[0] = new int[nextCap];
                bufs[1] = new int[nextCap];
                bufs[2] = new int[nextCap];
            }
        }
    }

    private static final ThreadLocal<AxisScratch> AXIS_SCRATCH = ThreadLocal.withInitial(AxisScratch::new);

    private static final String MERGER_INTF = "net/minecraft/world/phys/shapes/IndexMerger";
    private static final String CONSUMER_INTF = "net/minecraft/world/phys/shapes/IndexMerger$IndexConsumer";
    private static final String HELPER_CLS = "net/minecraft/world/phys/shapes/SunlitMergerHelper";
    private static volatile MethodHandle MERGER_SIZE_MH;
    private static volatile MethodHandle MERGER_COLLECT_MH;

    public static void initLookup(MethodHandles.Lookup pkgLookup) {
        if (MERGER_SIZE_MH != null) {
            return;
        }
        try {
            byte[] code = generateHelperClass();
            MethodHandles.Lookup hidden = pkgLookup.defineHiddenClass(code, true);
            Class<?> helperClass = hidden.lookupClass();
            MethodHandle sizeMh = hidden.findStatic(helperClass, "size", MethodType.methodType(int.class, Object.class));
            MethodHandle collectMh = hidden.findStatic(
                    helperClass,
                    "collect",
                    MethodType.methodType(int.class, Object.class, int[].class, int[].class, int[].class));
            MERGER_COLLECT_MH = collectMh;
            MERGER_SIZE_MH = sizeMh;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private VoxelJoinPerf() {
    }

    private static byte[] generateHelperClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER_CLS,
                null,
                "java/lang/Object",
                new String[] { CONSUMER_INTF });

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "i1", "[I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "i2", "[I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "ir", "[I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "count", "I", null, null).visitEnd();

        // <init>(int[] i1, int[] i2, int[] ir)
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "([I[I[I)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, HELPER_CLS, "i1", "[I");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitFieldInsn(Opcodes.PUTFIELD, HELPER_CLS, "i2", "[I");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 3);
        init.visitFieldInsn(Opcodes.PUTFIELD, HELPER_CLS, "ir", "[I");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // public boolean m_82908_(int a, int b, int c)
        MethodVisitor consume = cw.visitMethod(Opcodes.ACC_PUBLIC, "m_82908_", "(III)Z", null, null);
        consume.visitCode();
        Label overflow = new Label();
        consume.visitVarInsn(Opcodes.ALOAD, 0);
        consume.visitFieldInsn(Opcodes.GETFIELD, HELPER_CLS, "count", "I");
        consume.visitVarInsn(Opcodes.ISTORE, 4);
        consume.visitVarInsn(Opcodes.ILOAD, 4);
        consume.visitVarInsn(Opcodes.ALOAD, 0);
        consume.visitFieldInsn(Opcodes.GETFIELD, HELPER_CLS, "i1", "[I");
        consume.visitInsn(Opcodes.ARRAYLENGTH);
        consume.visitJumpInsn(Opcodes.IF_ICMPGE, overflow);

        consume.visitVarInsn(Opcodes.ALOAD, 0);
        consume.visitFieldInsn(Opcodes.GETFIELD, HELPER_CLS, "i1", "[I");
        consume.visitVarInsn(Opcodes.ILOAD, 4);
        consume.visitVarInsn(Opcodes.ILOAD, 1);
        consume.visitInsn(Opcodes.IASTORE);

        consume.visitVarInsn(Opcodes.ALOAD, 0);
        consume.visitFieldInsn(Opcodes.GETFIELD, HELPER_CLS, "i2", "[I");
        consume.visitVarInsn(Opcodes.ILOAD, 4);
        consume.visitVarInsn(Opcodes.ILOAD, 2);
        consume.visitInsn(Opcodes.IASTORE);

        consume.visitVarInsn(Opcodes.ALOAD, 0);
        consume.visitFieldInsn(Opcodes.GETFIELD, HELPER_CLS, "ir", "[I");
        consume.visitVarInsn(Opcodes.ILOAD, 4);
        consume.visitVarInsn(Opcodes.ILOAD, 3);
        consume.visitInsn(Opcodes.IASTORE);

        consume.visitVarInsn(Opcodes.ALOAD, 0);
        consume.visitVarInsn(Opcodes.ILOAD, 4);
        consume.visitInsn(Opcodes.ICONST_1);
        consume.visitInsn(Opcodes.IADD);
        consume.visitFieldInsn(Opcodes.PUTFIELD, HELPER_CLS, "count", "I");
        consume.visitInsn(Opcodes.ICONST_1);
        consume.visitInsn(Opcodes.IRETURN);

        consume.visitLabel(overflow);
        consume.visitVarInsn(Opcodes.ALOAD, 0);
        consume.visitInsn(Opcodes.ICONST_M1);
        consume.visitFieldInsn(Opcodes.PUTFIELD, HELPER_CLS, "count", "I");
        consume.visitInsn(Opcodes.ICONST_0);
        consume.visitInsn(Opcodes.IRETURN);
        consume.visitMaxs(0, 0);
        consume.visitEnd();

        // public static int size(Object merger)
        MethodVisitor sizeM = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "size", "(Ljava/lang/Object;)I", null, null);
        sizeM.visitCode();
        sizeM.visitVarInsn(Opcodes.ALOAD, 0);
        sizeM.visitTypeInsn(Opcodes.CHECKCAST, MERGER_INTF);
        sizeM.visitMethodInsn(Opcodes.INVOKEINTERFACE, MERGER_INTF, "size", "()I", true);
        sizeM.visitInsn(Opcodes.IRETURN);
        sizeM.visitMaxs(0, 0);
        sizeM.visitEnd();

        // public static int collect(Object merger, int[] i1, int[] i2, int[] ir)
        MethodVisitor colM = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "collect", "(Ljava/lang/Object;[I[I[I)I", null, null);
        colM.visitCode();
        colM.visitTypeInsn(Opcodes.NEW, HELPER_CLS);
        colM.visitInsn(Opcodes.DUP);
        colM.visitVarInsn(Opcodes.ALOAD, 1);
        colM.visitVarInsn(Opcodes.ALOAD, 2);
        colM.visitVarInsn(Opcodes.ALOAD, 3);
        colM.visitMethodInsn(Opcodes.INVOKESPECIAL, HELPER_CLS, "<init>", "([I[I[I)V", false);
        colM.visitVarInsn(Opcodes.ASTORE, 4);
        colM.visitVarInsn(Opcodes.ALOAD, 0);
        colM.visitTypeInsn(Opcodes.CHECKCAST, MERGER_INTF);
        colM.visitVarInsn(Opcodes.ALOAD, 4);
        colM.visitMethodInsn(Opcodes.INVOKEINTERFACE, MERGER_INTF, "m_6200_", "(L" + CONSUMER_INTF + ";)Z", true);
        colM.visitInsn(Opcodes.POP);
        colM.visitVarInsn(Opcodes.ALOAD, 4);
        colM.visitFieldInsn(Opcodes.GETFIELD, HELPER_CLS, "count", "I");
        colM.visitInsn(Opcodes.IRETURN);
        colM.visitMaxs(0, 0);
        colM.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    public static BitSetDiscreteVoxelShape join(
            DiscreteVoxelShape s1,
            DiscreteVoxelShape s2,
            Object mx,
            Object my,
            Object mz,
            BooleanOp op) {
        int nx;
        int ny;
        int nz;
        try {
            nx = Math.max(0, (int) MERGER_SIZE_MH.invokeExact(mx) - 1);
            ny = Math.max(0, (int) MERGER_SIZE_MH.invokeExact(my) - 1);
            nz = Math.max(0, (int) MERGER_SIZE_MH.invokeExact(mz) - 1);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }

        BitSetDiscreteVoxelShape out = new BitSetDiscreteVoxelShape(nx, ny, nz);

        AxisScratch scratch = AXIS_SCRATCH.get();
        int xLen = scratch.collect(0, mx, nx + 1);
        int yLen = scratch.collect(1, my, ny + 1);
        int zLen = scratch.collect(2, mz, nz + 1);

        int xMin = Integer.MAX_VALUE;
        int yMin = Integer.MAX_VALUE;
        int zMin = Integer.MAX_VALUE;
        int xMax = Integer.MIN_VALUE;
        int yMax = Integer.MIN_VALUE;
        int zMax = Integer.MIN_VALUE;

        BitSet bs = ((BitSetShapeTarget) (Object) out).sunlitGetStorage();
        int[] x1 = scratch.axis[0][0], x2 = scratch.axis[0][1], rx = scratch.axis[0][2];
        int[] y1 = scratch.axis[1][0], y2 = scratch.axis[1][1], ry = scratch.axis[1][2];
        int[] z1 = scratch.axis[2][0], z2 = scratch.axis[2][1], rz = scratch.axis[2][2];

        if (s1 instanceof BitSetShapeTarget t1 && s2 instanceof BitSetShapeTarget t2) {
            BitSet bs1 = t1.sunlitGetStorage();
            BitSet bs2 = t2.sunlitGetStorage();
            int sx1 = t1.sunlitGetXSize(), sy1 = t1.sunlitGetYSize(), sz1 = t1.sunlitGetZSize();
            int sx2 = t2.sunlitGetXSize(), sy2 = t2.sunlitGetYSize(), sz2 = t2.sunlitGetZSize();
            if (op == BooleanOp.f_82695_) {
                for (int ix = 0; ix < xLen; ix++) {
                    int ax1 = x1[ix];
                    int ax2 = x2[ix];
                    int arx = rx[ix];
                    boolean x1Ok = ax1 >= 0 && ax1 < sx1;
                    boolean x2Ok = ax2 >= 0 && ax2 < sx2;
                    if (!x1Ok && !x2Ok) {
                        continue;
                    }
                    int xPlane = arx * ny;
                    int xOff1 = ax1 * sy1;
                    int xOff2 = ax2 * sy2;
                    boolean xSet = false;
                    for (int iy = 0; iy < yLen; iy++) {
                        int ay1 = y1[iy];
                        int ay2 = y2[iy];
                        boolean in1 = x1Ok && ay1 >= 0 && ay1 < sy1;
                        boolean in2 = x2Ok && ay2 >= 0 && ay2 < sy2;
                        if (!in1 && !in2) {
                            continue;
                        }
                        int ary = ry[iy];
                        int xyOff = (xPlane + ary) * nz;
                        int row1 = (xOff1 + ay1) * sz1;
                        int row2 = (xOff2 + ay2) * sz2;
                        boolean rowSet = false;
                        for (int iz = 0; iz < zLen; iz++) {
                            int az1;
                            boolean hit = in1 && (az1 = z1[iz]) >= 0 && az1 < sz1 && bs1.get(row1 + az1);
                            if (!hit && in2) {
                                int az2 = z2[iz];
                                hit = az2 >= 0 && az2 < sz2 && bs2.get(row2 + az2);
                            }
                            if (hit) {
                                int arz = rz[iz];
                                bs.set(xyOff + arz);
                                rowSet = true;
                                if (arz < zMin) zMin = arz;
                                if (arz > zMax) zMax = arz;
                            }
                        }
                        if (rowSet) {
                            xSet = true;
                            if (ary < yMin) yMin = ary;
                            if (ary > yMax) yMax = ary;
                        }
                    }
                    if (xSet) {
                        if (arx < xMin) xMin = arx;
                        if (arx > xMax) xMax = arx;
                    }
                }
            } else if (op == BooleanOp.f_82689_) {
                for (int ix = 0; ix < xLen; ix++) {
                    int ax1 = x1[ix];
                    int ax2 = x2[ix];
                    if (ax1 < 0 || ax1 >= sx1 || ax2 < 0 || ax2 >= sx2) {
                        continue;
                    }
                    int arx = rx[ix];
                    int xPlane = arx * ny;
                    int xOff1 = ax1 * sy1;
                    int xOff2 = ax2 * sy2;
                    boolean xSet = false;
                    for (int iy = 0; iy < yLen; iy++) {
                        int ay1 = y1[iy];
                        int ay2 = y2[iy];
                        if (ay1 < 0 || ay1 >= sy1 || ay2 < 0 || ay2 >= sy2) {
                            continue;
                        }
                        int ary = ry[iy];
                        int xyOff = (xPlane + ary) * nz;
                        int row1 = (xOff1 + ay1) * sz1;
                        int row2 = (xOff2 + ay2) * sz2;
                        boolean rowSet = false;
                        for (int iz = 0; iz < zLen; iz++) {
                            int az1 = z1[iz];
                            if (az1 >= 0 && az1 < sz1 && bs1.get(row1 + az1)) {
                                int az2 = z2[iz];
                                if (az2 >= 0 && az2 < sz2 && bs2.get(row2 + az2)) {
                                    int arz = rz[iz];
                                    bs.set(xyOff + arz);
                                    rowSet = true;
                                    if (arz < zMin) zMin = arz;
                                    if (arz > zMax) zMax = arz;
                                }
                            }
                        }
                        if (rowSet) {
                            xSet = true;
                            if (ary < yMin) yMin = ary;
                            if (ary > yMax) yMax = ary;
                        }
                    }
                    if (xSet) {
                        if (arx < xMin) xMin = arx;
                        if (arx > xMax) xMax = arx;
                    }
                }
            } else {
                for (int ix = 0; ix < xLen; ix++) {
                    int ax1 = x1[ix];
                    int ax2 = x2[ix];
                    int arx = rx[ix];
                    boolean x1Ok = ax1 >= 0 && ax1 < sx1;
                    boolean x2Ok = ax2 >= 0 && ax2 < sx2;
                    int xPlane = arx * ny;
                    int xOff1 = ax1 * sy1;
                    int xOff2 = ax2 * sy2;
                    boolean xSet = false;
                    for (int iy = 0; iy < yLen; iy++) {
                        int ay1 = y1[iy];
                        int ay2 = y2[iy];
                        boolean in1 = x1Ok && ay1 >= 0 && ay1 < sy1;
                        boolean in2 = x2Ok && ay2 >= 0 && ay2 < sy2;
                        int ary = ry[iy];
                        int xyOff = (xPlane + ary) * nz;
                        int row1 = (xOff1 + ay1) * sz1;
                        int row2 = (xOff2 + ay2) * sz2;
                        boolean rowSet = false;
                        for (int iz = 0; iz < zLen; iz++) {
                            int az1;
                            boolean v1 = in1 && (az1 = z1[iz]) >= 0 && az1 < sz1 && bs1.get(row1 + az1);
                            int az2;
                            boolean v2 = in2 && (az2 = z2[iz]) >= 0 && az2 < sz2 && bs2.get(row2 + az2);
                            if (op.m_82701_(v1, v2)) {
                                int arz = rz[iz];
                                bs.set(xyOff + arz);
                                rowSet = true;
                                if (arz < zMin) zMin = arz;
                                if (arz > zMax) zMax = arz;
                            }
                        }
                        if (rowSet) {
                            xSet = true;
                            if (ary < yMin) yMin = ary;
                            if (ary > yMax) yMax = ary;
                        }
                    }
                    if (xSet) {
                        if (arx < xMin) xMin = arx;
                        if (arx > xMax) xMax = arx;
                    }
                }
            }
            ((BitSetShapeTarget) (Object) out).sunlitSetBounds(
                    xMin, yMin, zMin, xMax + 1, yMax + 1, zMax + 1);
            return out;
        }

        if (op == BooleanOp.f_82695_) {
            for (int ix = 0; ix < xLen; ix++) {
                int ax1 = x1[ix];
                int ax2 = x2[ix];
                int arx = rx[ix];
                int xPlane = arx * ny;
                for (int iy = 0; iy < yLen; iy++) {
                    int ay1 = y1[iy];
                    int ay2 = y2[iy];
                    int ary = ry[iy];
                    int xyOff = (xPlane + ary) * nz;
                    for (int iz = 0; iz < zLen; iz++) {
                        if (s1.m_82846_(ax1, ay1, z1[iz]) || s2.m_82846_(ax2, ay2, z2[iz])) {
                            int arz = rz[iz];
                            bs.set(xyOff + arz);
                            if (arx < xMin) xMin = arx;
                            if (arx > xMax) xMax = arx;
                            if (ary < yMin) yMin = ary;
                            if (ary > yMax) yMax = ary;
                            if (arz < zMin) zMin = arz;
                            if (arz > zMax) zMax = arz;
                        }
                    }
                }
            }
        } else if (op == BooleanOp.f_82689_) {
            for (int ix = 0; ix < xLen; ix++) {
                int ax1 = x1[ix];
                int ax2 = x2[ix];
                int arx = rx[ix];
                int xPlane = arx * ny;
                for (int iy = 0; iy < yLen; iy++) {
                    int ay1 = y1[iy];
                    int ay2 = y2[iy];
                    int ary = ry[iy];
                    int xyOff = (xPlane + ary) * nz;
                    for (int iz = 0; iz < zLen; iz++) {
                        if (s1.m_82846_(ax1, ay1, z1[iz]) && s2.m_82846_(ax2, ay2, z2[iz])) {
                            int arz = rz[iz];
                            bs.set(xyOff + arz);
                            if (arx < xMin) xMin = arx;
                            if (arx > xMax) xMax = arx;
                            if (ary < yMin) yMin = ary;
                            if (ary > yMax) yMax = ary;
                            if (arz < zMin) zMin = arz;
                            if (arz > zMax) zMax = arz;
                        }
                    }
                }
            }
        } else {
            for (int ix = 0; ix < xLen; ix++) {
                int ax1 = x1[ix];
                int ax2 = x2[ix];
                int arx = rx[ix];
                int xPlane = arx * ny;
                for (int iy = 0; iy < yLen; iy++) {
                    int ay1 = y1[iy];
                    int ay2 = y2[iy];
                    int ary = ry[iy];
                    int xyOff = (xPlane + ary) * nz;
                    for (int iz = 0; iz < zLen; iz++) {
                        if (op.m_82701_(s1.m_82846_(ax1, ay1, z1[iz]), s2.m_82846_(ax2, ay2, z2[iz]))) {
                            int arz = rz[iz];
                            bs.set(xyOff + arz);
                            if (arx < xMin) xMin = arx;
                            if (arx > xMax) xMax = arx;
                            if (ary < yMin) yMin = ary;
                            if (ary > yMax) yMax = ary;
                            if (arz < zMin) zMin = arz;
                            if (arz > zMax) zMax = arz;
                        }
                    }
                }
            }
        }

        ((BitSetShapeTarget) (Object) out).sunlitSetBounds(
                xMin, yMin, zMin, xMax + 1, yMax + 1, zMax + 1);
        return out;
    }
}
