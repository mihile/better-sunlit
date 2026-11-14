package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.EmfRenderPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import traben.entity_model_features.EMF;
import traben.entity_model_features.models.animation.math.variables.EMFModelOrRenderVariable;
import traben.entity_model_features.models.animation.math.variables.factories.UniqueVariableFactory;
import traben.entity_model_features.utils.EMFUtils;

/**
 * Eliminates per-variable {@code String.split("\\.")} and {@code String.matches(...)} regex
 * compilation in EMF's {@code ModelPartVariableFactory.createsThisVariable}.
 */
@Pseudo
@Mixin(
        targets = "traben.entity_model_features.models.animation.math.variables.factories.ModelPartVariableFactory",
        priority = 2000,
        remap = false
)
public abstract class PerfEmfModelPartVariableMixin extends UniqueVariableFactory {
    /**
     * @author better-sunlit
     * @reason Zero-allocation syntax and protected 'render.*' variable check
     */
    @Override
    @Overwrite(remap = false)
    public boolean createsThisVariable(String var1) {
        if (var1 == null) {
            return false;
        }
        if ((var1.equals("render") || var1.startsWith("render."))
                && EMF.config().getConfig().enforceOptiFineAnimSyntaxLimits) {
            if (var1.length() > 7) {
                int dot2 = var1.indexOf('.', 7);
                String second = dot2 < 0 ? var1.substring(7) : var1.substring(7, dot2);
                if (EMFModelOrRenderVariable.get(second) != null && this.printing()) {
                    EMFUtils.logError("Model part variable [" + var1 + "] is not allowed, 'render' is a protected animation key name.");
                }
            }
            return false;
        }
        return EmfRenderPerf.matchesModelPartVariableSyntax(var1);
    }
}
