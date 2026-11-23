import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sunlit.bettersunlit.client.ClientPostApplyPatches;
import dev.sunlit.bettersunlit.client.EmfValidationScope;
import dev.sunlit.fishvisuals.mixin.PerfEmfManagerInitMixin;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import traben.entity_model_features.EMFManager;
import traben.entity_model_features.models.animation.math.asm.ASMVariableHandler;
import traben.entity_model_features.models.animation.math.expression_tree.MathComponent;
import traben.entity_model_features.models.animation.math.expression_tree.MathOperator;
import traben.entity_model_features.models.jem_objects.EMFJemData;
import traben.entity_model_features.models.parts.EMFModelPartRoot;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public final class EmfAnimationValidationTests {
    private static final MathComponent ZERO_VARIABLE = new MathComponent() {
        @Override public float getResult() { return 0; }
        @Override public boolean isConstant() { return false; }
        @Override public void asmVisit(MethodVisitor visitor, ASMVariableHandler handler) {
            throw new UnsupportedOperationException();
        }
    };

    public static void main(String[] args) throws Exception {
        var manager = EMFManager.getInstance();
        var validating = new CountDownLatch(1);
        var concurrentFinished = new CountDownLatch(1);
        var unguarded = new FutureTask<>(() -> {
            manager.isAnimationValidationPhase = true;
            validating.countDown();
            await(concurrentFinished);
            return divide();
        });
        var worker = new Thread(unguarded, "unguarded-animation");
        worker.start();
        await(validating);
        manager.isAnimationValidationPhase = true;
        manager.isAnimationValidationPhase = false;
        concurrentFinished.countDown();
        check(Float.isNaN(unguarded.get(5, TimeUnit.SECONDS)), "unlocked EMF validation race reproduced");

        var mixin = new PerfEmfManagerInitMixin() {};
        Method wrapper = PerfEmfManagerInitMixin.class.getDeclaredMethod("sunlit$validateAnimationsSerially",
                EMFJemData.class, EMFModelPartRoot.class, int.class, Operation.class);
        wrapper.setAccessible(true);
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var first = new FutureTask<>(() -> {
            wrapper.invoke(mixin, null, null, 1, (Operation<Void>) parameters -> {
                manager.isAnimationValidationPhase = true;
                firstEntered.countDown();
                try {
                    await(releaseFirst);
                    check(divide() == 0.0f, "second validation changed the active flag");
                } finally {
                    manager.isAnimationValidationPhase = false;
                }
                return null;
            });
            return null;
        });
        new Thread(first, "first-animation").start();
        try {
            await(firstEntered);
            var second = new FutureTask<>(() -> {
                wrapper.invoke(mixin, null, null, 2, (Operation<Void>) parameters -> {
                    check(!manager.isAnimationValidationPhase, "validations overlapped");
                    manager.isAnimationValidationPhase = true;
                    try {
                        check(divide() == 0.0f, "second validation did not retain its flag");
                    } finally {
                        manager.isAnimationValidationPhase = false;
                    }
                    return null;
                });
                return null;
            });
            var peer = new Thread(second, "second-animation");
            peer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!second.isDone() && peer.getState() != Thread.State.BLOCKED
                    && System.nanoTime() < deadline) {
                LockSupport.parkNanos(100_000);
            }
            check(peer.getState() == Thread.State.BLOCKED, "second validation did not wait for the first");
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
        check(!manager.isAnimationValidationPhase, "validation flag left enabled");
        testScopedValidation(manager);
        System.out.println("PASS: real EMF divide-by-zero validation race reproduced and serialized wrapper verified.");
    }

    private static void testScopedValidation(EMFManager manager) throws Exception {
        String root = "traben/entity_model_features/";
        String math = root + "models/animation/math/";
        String[] targets = {root + "EMFManager", math + "asm/ASMHelper", math + "expression_tree/MathOperator$4",
                math + "expression_tree/MathOperator$5", math + "methods/optifine/PrintBMethod",
                math + "methods/optifine/PrintMethod", math + "variables/VariableRegistry"};
        for (String target : targets) {
            var node = new ClassNode();
            try (var in = manager.getClass().getClassLoader().getResourceAsStream(target + ".class")) {
                new ClassReader(in).accept(node, 0);
            }
            ClientPostApplyPatches.patchEmfValidationFlag(node);
            check(node.fields.stream().anyMatch(f -> f.name.equals("sunlit$scopedAnimationValidation")),
                    "Missing scoped flag marker for " + target);
        }
        check(!EmfValidationScope.ready(manager), "An unpatched EMF instance must use the serial wrapper");

        var entered = new CountDownLatch(1);
        var peerDone = new CountDownLatch(1);
        var first = new FutureTask<>(() -> EmfValidationScope.bake(manager, () -> {
            EmfValidationScope.write(manager, true);
            entered.countDown();
            await(peerDone);
            check(EmfValidationScope.read(manager), "Another worker reset the local flag");
            check(!manager.isAnimationValidationPhase, "A worker wrote the shared flag");
            return null;
        }));
        new Thread(first, "scoped-animation").start();
        try {
            await(entered);
            check(!EmfValidationScope.read(manager), "Caller inherited a worker validation scope");
            EmfValidationScope.bake(manager, () -> {
                EmfValidationScope.write(manager, true);
                EmfValidationScope.write(manager, false);
                return null;
            });
            check(!EmfValidationScope.isScoped(manager), "Caller scope leaked");
        } finally {
            peerDone.countDown();
        }
        first.get(5, TimeUnit.SECONDS);
    }

    private static float divide() {
        return MathOperator.DIVIDE.execute(ZERO_VARIABLE, ZERO_VARIABLE);
    }

    private static void await(CountDownLatch latch) {
        try {
            check(latch.await(5, TimeUnit.SECONDS), "validation worker timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
