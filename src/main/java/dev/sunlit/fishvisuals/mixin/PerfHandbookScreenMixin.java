package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.LongwingsVariantPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.cicada.longwings.client.gui.HandbookScreen", remap = false)
public abstract class PerfHandbookScreenMixin {
    @Redirect(
            method = {
                "renderLocatePortion(Lnet/minecraft/client/gui/GuiGraphics;IIFLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                "renderLurePortion(Lnet/minecraft/client/gui/GuiGraphics;IIFLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                "renderSpeciesPage(Lnet/minecraft/client/gui/GuiGraphics;IIFLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
            },
            at = @At(value = "INVOKE", target = "Ljava/lang/String;matches(Ljava/lang/String;)Z"),
            require = 3,
            remap = false)
    private boolean sunlit$fastMatches(String candidate, String pattern) {
        return LongwingsVariantPerf.matches(candidate, pattern);
    }

    @Redirect(
            method = "lambda$buildAtlasPage$0(Lcom/cicada/longwings/entity/ButterflyVariant;Ljava/lang/String;)Z",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;matches(Ljava/lang/String;)Z"),
            require = 1,
            remap = false)
    private static boolean sunlit$fastMatchesStatic(String candidate, String pattern) {
        return LongwingsVariantPerf.matches(candidate, pattern);
    }
}
