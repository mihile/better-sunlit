package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.LongwingsVariantPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.cicada.longwings.client.gui.HandbookScreen$ButterflyIndexButton", remap = false)
public abstract class PerfHandbookIndexButtonMixin {
    @Redirect(
            method = "m_87963_(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;matches(Ljava/lang/String;)Z"),
            require = 1,
            remap = false)
    private boolean sunlit$fastMatches(String candidate, String pattern) {
        return LongwingsVariantPerf.matches(candidate, pattern);
    }
}
