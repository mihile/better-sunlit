package dev.sunlit.fishvisuals.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = {
        "traben.entity_model_features.EMFManager",
        "traben.entity_model_features.models.animation.math.asm.ASMHelper",
        "traben.entity_model_features.models.animation.math.expression_tree.MathOperator$4",
        "traben.entity_model_features.models.animation.math.expression_tree.MathOperator$5",
        "traben.entity_model_features.models.animation.math.methods.optifine.PrintBMethod",
        "traben.entity_model_features.models.animation.math.methods.optifine.PrintMethod",
        "traben.entity_model_features.models.animation.math.variables.VariableRegistry"
}, remap = false)
public abstract class PerfEmfValidationFlagMixin {}
