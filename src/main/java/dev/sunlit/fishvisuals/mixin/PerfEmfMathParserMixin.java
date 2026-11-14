package dev.sunlit.fishvisuals.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Anchor mixin for post-apply ASM rewrite of {@code MathExpressionParser.<init>} stream iteration.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.animation.math.expression_tree.MathExpressionParser", priority = 2000, remap = false)
public abstract class PerfEmfMathParserMixin {
}
