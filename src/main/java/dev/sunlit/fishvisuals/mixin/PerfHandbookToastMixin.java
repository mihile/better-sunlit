package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.LongwingsVariantPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.cicada.longwings.client.gui.HandbookToast", remap = false)
public abstract class PerfHandbookToastMixin {
    @Redirect(
            method = "m_7172_(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/components/toasts/ToastComponent;J)Lnet/minecraft/client/gui/components/toasts/Toast$Visibility;",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;matches(Ljava/lang/String;)Z"),
            require = 1,
            remap = false)
    private boolean sunlit$fastMatches(String candidate, String pattern) {
        return LongwingsVariantPerf.matches(candidate, pattern);
    }
}
