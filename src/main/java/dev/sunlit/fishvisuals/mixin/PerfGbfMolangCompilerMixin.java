package dev.sunlit.fishvisuals.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Anchor mixin for {@code ClientPostApplyPatches.patchGbfMolangCompiler}, which wraps non-constant
 * Molang ASTs in a lazy {@code IValue} instead of compiling bytecode eagerly during asset reload.
 */
@Pseudo
@Mixin(targets = "com.moepus.gbf.molang.compiler.MolangCompiler", remap = false)
public abstract class PerfGbfMolangCompilerMixin {
}
