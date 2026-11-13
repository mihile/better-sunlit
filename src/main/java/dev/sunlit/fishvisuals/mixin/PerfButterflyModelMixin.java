package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.LongwingsVariantPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.cicada.longwings.client.model.ButterflyModel", remap = false)
public abstract class PerfButterflyModelMixin {
    @Redirect(
            method = "setupAnim(Lcom/cicada/longwings/entity/ButterflyEntity;FFFFF)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;matches(Ljava/lang/String;)Z"),
            require = 1,
            remap = false)
    private boolean sunlit$fastMatches(String candidate, String pattern) {
        return LongwingsVariantPerf.matches(candidate, pattern);
    }
}
