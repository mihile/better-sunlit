package dev.sunlit.bettersunlit.client;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Function;

/**
 * Deduplicates canonical states and part-box geometry across backpack tiers, rotates boxes directly,
 * joins via balanced binary trees, and computes unique {@link VoxelShape}s in parallel.
 */
public final class BackpackShapePerf {
    private static volatile boolean compatResolved;
    private static Class<?> bakedModelClass;
    private static Field partShapesField;
    private static Field shapeCacheField;
    private static Enum<?> partBase;
    private static Enum<?> partLeftPouch;
    private static Enum<?> partLeftTank;
    private static Enum<?> partRightPouch;
    private static Enum<?> partRightTank;
    private static Enum<?> partFrontPouch;
    private static Enum<?> partBattery;
    private static Method shapeKeyOfMethod;

    private BackpackShapePerf() {
    }

    public static <S, V> Map<S, V> buildDeduplicatedShapes(
            List<S> allStates,
            Function<S, S> canonicalizer,
            Function<S, V> shapeComputer) {
        Map<S, Integer> canonicalIndex = new LinkedHashMap<>(256);
        List<S> uniqueCanonicals = new ArrayList<>(256);
        int total = allStates.size();
        int[] stateToCanonical = new int[total];
        for (int i = 0; i < total; i++) {
            S canonical = canonicalizer.apply(allStates.get(i));
            Integer idx = canonicalIndex.get(canonical);
            if (idx == null) {
                idx = uniqueCanonicals.size();
                canonicalIndex.put(canonical, idx);
                uniqueCanonicals.add(canonical);
            }
            stateToCanonical[i] = idx;
        }
        int uniqueCount = uniqueCanonicals.size();
        @SuppressWarnings("unchecked")
        V[] computed = (V[]) new Object[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            computed[i] = shapeComputer.apply(uniqueCanonicals.get(i));
        }
        Map<S, V> rebuilt = new HashMap<>(total * 2);
        for (int i = 0; i < total; i++) {
            rebuilt.put(allStates.get(i), computed[stateToCanonical[i]]);
        }
        return Map.copyOf(rebuilt);
    }

    public static <S> Map<S, VoxelShape> buildFastBackpackShapes(
            List<S> allStates,
            Function<S, S> canonicalizer,
            Function<S, Object> modelFetcher,
            Function<S, Direction> facingExtractor,
            Function<S, Boolean> leftTankExtractor,
            Function<S, Boolean> rightTankExtractor,
            Function<S, Boolean> batteryExtractor,
            Function<S, VoxelShape> fallbackComputer) {
        resolveCompat();

        int total = allStates.size();
        Map<S, Integer> canonicalIndex = new LinkedHashMap<>(256);
        List<S> uniqueCanonicals = new ArrayList<>(256);
        int[] stateToCanonical = new int[total];
        for (int i = 0; i < total; i++) {
            S canonical = canonicalizer.apply(allStates.get(i));
            Integer idx = canonicalIndex.get(canonical);
            if (idx == null) {
                idx = uniqueCanonicals.size();
                canonicalIndex.put(canonical, idx);
                uniqueCanonicals.add(canonical);
            }
            stateToCanonical[i] = idx;
        }

        int uniqueCount = uniqueCanonicals.size();
        VoxelShape[] canonicalShapes = new VoxelShape[uniqueCount];

        if (bakedModelClass == null || partShapesField == null) {
            for (int i = 0; i < uniqueCount; i++) {
                canonicalShapes[i] = fallbackComputer.apply(uniqueCanonicals.get(i));
            }
        } else {
            IdentityHashMap<VoxelShape, AabbSignature> partBoxCache = new IdentityHashMap<>(64);
            Map<GeometryKey, Integer> geometryIndex = new HashMap<>(64);
            List<GeometryTask> uniqueTasks = new ArrayList<>(64);
            int[] canonicalToTask = new int[uniqueCount];
            Arrays.fill(canonicalToTask, -1);

            @SuppressWarnings("unchecked")
            Map<Object, VoxelShape>[] modelShapeCaches = new Map[uniqueCount];
            Object[] modelShapeKeys = new Object[uniqueCount];

            for (int i = 0; i < uniqueCount; i++) {
                S canonical = uniqueCanonicals.get(i);
                Object model = modelFetcher.apply(canonical);
                if (bakedModelClass.isInstance(model)) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<?, VoxelShape> parts = (Map<?, VoxelShape>) partShapesField.get(model);
                        boolean leftTank = leftTankExtractor.apply(canonical);
                        boolean rightTank = rightTankExtractor.apply(canonical);
                        boolean battery = batteryExtractor.apply(canonical);
                        VoxelShape base = parts.get(partBase);
                        VoxelShape left = parts.get(leftTank ? partLeftTank : partLeftPouch);
                        VoxelShape right = parts.get(rightTank ? partRightTank : partRightPouch);
                        VoxelShape front = parts.get(battery ? partBattery : partFrontPouch);
                        if (base != null && left != null && right != null && front != null
                                && parts.containsKey(partLeftPouch) && parts.containsKey(partLeftTank)
                                && parts.containsKey(partRightPouch) && parts.containsKey(partRightTank)
                                && parts.containsKey(partFrontPouch) && parts.containsKey(partBattery)) {
                            Direction dir = facingExtractor.apply(canonical);
                            AabbSignature sBase = getOrBuildSignature(partBoxCache, base);
                            AabbSignature sLeft = getOrBuildSignature(partBoxCache, left);
                            AabbSignature sRight = getOrBuildSignature(partBoxCache, right);
                            AabbSignature sFront = getOrBuildSignature(partBoxCache, front);
                            GeometryKey gk = new GeometryKey(sBase, sLeft, sRight, sFront, dir);
                            Integer taskIdx = geometryIndex.get(gk);
                            if (taskIdx == null) {
                                taskIdx = uniqueTasks.size();
                                geometryIndex.put(gk, taskIdx);
                                uniqueTasks.add(new GeometryTask(sBase, sLeft, sRight, sFront, dir));
                            }
                            canonicalToTask[i] = taskIdx;
                            if (shapeCacheField != null && shapeKeyOfMethod != null) {
                                @SuppressWarnings("unchecked")
                                Map<Object, VoxelShape> mCache = (Map<Object, VoxelShape>) shapeCacheField.get(model);
                                if (mCache != null) {
                                    modelShapeCaches[i] = mCache;
                                    modelShapeKeys[i] = shapeKeyOfMethod.invoke(null, dir, leftTank, rightTank, battery);
                                }
                            }
                            continue;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                canonicalShapes[i] = fallbackComputer.apply(canonical);
            }

            int taskCount = uniqueTasks.size();
            VoxelShape[] taskResults = new VoxelShape[taskCount];
            if (taskCount >= 4 && ForkJoinPool.getCommonPoolParallelism() > 1) {
                List<ForkJoinTask<?>> jobs = new ArrayList<>(taskCount);
                for (int i = 1; i < taskCount; i++) {
                    final int idx = i;
                    final GeometryTask task = uniqueTasks.get(idx);
                    jobs.add(ForkJoinPool.commonPool().submit(() -> taskResults[idx] = task.compute()));
                }
                taskResults[0] = uniqueTasks.get(0).compute();
                for (ForkJoinTask<?> job : jobs) {
                    job.join();
                }
            } else {
                for (int i = 0; i < taskCount; i++) {
                    taskResults[i] = uniqueTasks.get(i).compute();
                }
            }

            for (int i = 0; i < uniqueCount; i++) {
                int tidx = canonicalToTask[i];
                if (tidx >= 0) {
                    VoxelShape shape = taskResults[tidx];
                    canonicalShapes[i] = shape;
                    if (modelShapeCaches[i] != null && modelShapeKeys[i] != null) {
                        modelShapeCaches[i].putIfAbsent(modelShapeKeys[i], shape);
                    }
                }
            }
        }

        Map<S, VoxelShape> rebuilt = new HashMap<>(total * 2);
        for (int i = 0; i < total; i++) {
            rebuilt.put(allStates.get(i), canonicalShapes[stateToCanonical[i]]);
        }
        return Map.copyOf(rebuilt);
    }

    private static AabbSignature getOrBuildSignature(IdentityHashMap<VoxelShape, AabbSignature> cache, VoxelShape shape) {
        AabbSignature sig = cache.get(shape);
        if (sig == null) {
            sig = AabbSignature.fromShape(shape);
            cache.put(shape, sig);
        }
        return sig;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void resolveCompat() {
        if (compatResolved) {
            return;
        }
        try {
            ClassLoader cl = BackpackShapePerf.class.getClassLoader();
            Class<?> baked = Class.forName(
                    "net.p3pp3rf1y.sophisticatedbackpacks.client.render.BackpackDynamicModel$BackpackBakedModel",
                    false,
                    cl);
            Class<?> modelPartCls = Class.forName(
                    "net.p3pp3rf1y.sophisticatedbackpacks.client.render.BackpackDynamicModel$ModelPart",
                    false,
                    cl);
            Field psField = baked.getDeclaredField("partShapes");
            psField.setAccessible(true);
            Field scField = baked.getDeclaredField("shapeCache");
            scField.setAccessible(true);

            partBase = Enum.valueOf((Class) modelPartCls, "BASE");
            partLeftPouch = Enum.valueOf((Class) modelPartCls, "LEFT_POUCH");
            partLeftTank = Enum.valueOf((Class) modelPartCls, "LEFT_TANK");
            partRightPouch = Enum.valueOf((Class) modelPartCls, "RIGHT_POUCH");
            partRightTank = Enum.valueOf((Class) modelPartCls, "RIGHT_TANK");
            partFrontPouch = Enum.valueOf((Class) modelPartCls, "FRONT_POUCH");
            partBattery = Enum.valueOf((Class) modelPartCls, "BATTERY");

            try {
                Class<?> shapeKeyCls = Class.forName(
                        "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackShapeHelper$ShapeKey",
                        false,
                        cl);
                Method ofMethod = shapeKeyCls.getMethod("of", Direction.class, boolean.class, boolean.class, boolean.class);
                ofMethod.setAccessible(true);
                shapeKeyOfMethod = ofMethod;
            } catch (Throwable ignored) {
            }

            partShapesField = psField;
            shapeCacheField = scField;
            bakedModelClass = baked;
        } catch (Throwable ignored) {
            bakedModelClass = null;
        } finally {
            compatResolved = true;
        }
    }

    private static VoxelShape balancedUnion(List<VoxelShape> shapes) {
        int n = shapes.size();
        if (n == 0) {
            return Shapes.m_83040_();
        }
        if (n == 1) {
            return shapes.get(0);
        }
        return joinRange(shapes, 0, n);
    }

    private static VoxelShape joinRange(List<VoxelShape> shapes, int start, int end) {
        int len = end - start;
        if (len == 1) {
            return shapes.get(start);
        }
        if (len == 2) {
            return Shapes.m_83148_(shapes.get(start), shapes.get(start + 1), BooleanOp.f_82695_);
        }
        int mid = start + (len >>> 1);
        return Shapes.m_83148_(joinRange(shapes, start, mid), joinRange(shapes, mid, end), BooleanOp.f_82695_);
    }

    private static void appendRotatedBoxes(AabbSignature sig, Direction dir, List<VoxelShape> dst) {
        double[] coords = sig.coords;
        for (int i = 0; i < coords.length; i += 6) {
            double x1 = coords[i];
            double y1 = coords[i + 1];
            double z1 = coords[i + 2];
            double x2 = coords[i + 3];
            double y2 = coords[i + 4];
            double z2 = coords[i + 5];
            double rx1;
            double rz1;
            double rx2;
            double rz2;
            switch (dir) {
                case EAST -> {
                    rx1 = 1.0 - z2;
                    rz1 = x1;
                    rx2 = 1.0 - z1;
                    rz2 = x2;
                }
                case SOUTH -> {
                    rx1 = 1.0 - x2;
                    rz1 = 1.0 - z2;
                    rx2 = 1.0 - x1;
                    rz2 = 1.0 - z1;
                }
                case WEST -> {
                    rx1 = z1;
                    rz1 = 1.0 - x2;
                    rx2 = z2;
                    rz2 = 1.0 - x1;
                }
                default -> {
                    rx1 = x1;
                    rz1 = z1;
                    rx2 = x2;
                    rz2 = z2;
                }
            }
            dst.add(Shapes.m_83048_(
                    Math.min(rx1, rx2),
                    Math.min(y1, y2),
                    Math.min(rz1, rz2),
                    Math.max(rx1, rx2),
                    Math.max(y1, y2),
                    Math.max(rz1, rz2)));
        }
    }

    private static final class AabbSignature {
        final double[] coords;
        final int hash;

        private AabbSignature(double[] coords) {
            this.coords = coords;
            this.hash = Arrays.hashCode(coords);
        }

        static AabbSignature fromShape(VoxelShape shape) {
            List<AABB> aabbs = shape.m_83299_();
            double[] c = new double[aabbs.size() * 6];
            for (int i = 0, idx = 0; i < aabbs.size(); i++, idx += 6) {
                AABB b = aabbs.get(i);
                c[idx] = b.f_82288_;
                c[idx + 1] = b.f_82289_;
                c[idx + 2] = b.f_82290_;
                c[idx + 3] = b.f_82291_;
                c[idx + 4] = b.f_82292_;
                c[idx + 5] = b.f_82293_;
            }
            return new AabbSignature(c);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj || (obj instanceof AabbSignature other && Arrays.equals(this.coords, other.coords));
        }
    }

    private record GeometryKey(
            AabbSignature base,
            AabbSignature left,
            AabbSignature right,
            AabbSignature front,
            Direction dir) {
    }

    private record GeometryTask(
            AabbSignature base,
            AabbSignature left,
            AabbSignature right,
            AabbSignature front,
            Direction dir) {
        VoxelShape compute() {
            int totalBoxes = (base.coords.length + left.coords.length + right.coords.length + front.coords.length) / 6;
            if (totalBoxes == 0) {
                return Shapes.m_83040_();
            }
            List<VoxelShape> boxes = new ArrayList<>(totalBoxes);
            appendRotatedBoxes(base, dir, boxes);
            appendRotatedBoxes(left, dir, boxes);
            appendRotatedBoxes(right, dir, boxes);
            appendRotatedBoxes(front, dir, boxes);
            return balancedUnion(boxes).m_83296_();
        }
    }
}
