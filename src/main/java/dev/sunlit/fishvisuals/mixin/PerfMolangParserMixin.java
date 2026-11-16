package dev.sunlit.fishvisuals.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import software.bernie.geckolib.core.molang.MolangParser;

/**
 * Suppresses noisy {@code Exception.printStackTrace()} STDERR dumps during failing Molang expression
 * fallbacks in {@code MolangParser.parseSymbolsMolang} that immediately throw {@code MolangException}.
 */
@Mixin(value = MolangParser.class, remap = false)
public abstract class PerfMolangParserMixin {
    @Redirect(
            method = "parseSymbolsMolang",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Exception;printStackTrace()V"))
    private void sunlit$suppressParseSymbolsPrintStackTrace(Exception ex) {
        // Callers catch MolangException cleanly; printing 289+ stack traces to stderr stalls startup.
    }
}
