package dev.sunlit.bettersunlit.client;

import net.minecraft.client.model.geom.ModelPart;
import traben.entity_model_features.EMFManager;

import java.util.function.Supplier;

/** Keeps EMF's validation flag local to a pre-bake worker on the reviewed EMF build. */
public final class EmfValidationScope {
    static final String MARKER = "sunlit$scopedAnimationValidation";
    private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();
    private static final ClassValue<Boolean> READY = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> manager) {
            String math = "traben.entity_model_features.models.animation.math.";
            String[] readers = {"asm.ASMHelper", "expression_tree.MathOperator$4", "expression_tree.MathOperator$5",
                    "methods.optifine.PrintBMethod", "methods.optifine.PrintMethod", "variables.VariableRegistry"};
            try {
                manager.getDeclaredField(MARKER);
                for (String reader : readers) {
                    Class.forName(math + reader, false, manager.getClassLoader()).getDeclaredField(MARKER);
                }
                return true;
            } catch (ReflectiveOperationException | LinkageError e) {
                return false;
            }
        }
    };

    private EmfValidationScope() {}

    public static boolean ready(EMFManager manager) {
        return READY.get(manager.getClass());
    }

    public static boolean isScoped(Object manager) {
        State state = ACTIVE.get();
        return state != null && state.manager == manager;
    }

    public static ModelPart bake(EMFManager manager, Supplier<ModelPart> work) {
        State previous = ACTIVE.get();
        ACTIVE.set(new State(manager));
        try {
            return work.get();
        } finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }

    public static boolean read(EMFManager manager) {
        State state = ACTIVE.get();
        return state != null && state.manager == manager ? state.validating : manager.isAnimationValidationPhase;
    }

    public static void write(EMFManager manager, boolean validating) {
        State state = ACTIVE.get();
        if (state != null && state.manager == manager) state.validating = validating;
        else manager.isAnimationValidationPhase = validating;
    }

    private static final class State {
        private final EMFManager manager;
        private boolean validating;

        private State(EMFManager manager) {
            this.manager = manager;
        }
    }
}
