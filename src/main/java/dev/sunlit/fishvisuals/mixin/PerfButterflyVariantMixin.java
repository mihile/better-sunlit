package dev.sunlit.fishvisuals.mixin;

import com.cicada.longwings.entity.ButterflyVariant;
import dev.sunlit.bettersunlit.perf.LongwingsVariantPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;

@Pseudo
@Mixin(targets = "com.cicada.longwings.entity.ButterflyVariant", remap = false)
public abstract class PerfButterflyVariantMixin {
    @Shadow
    private static ButterflyVariant[] butterflyVariants;

    @Shadow
    private static ButterflyVariant[] mothVariants;

    @Inject(method = "getAllVariantPool()Ljava/util/ArrayList;",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private static void sunlit$cachedAllVariantPool(CallbackInfoReturnable<ArrayList<ButterflyVariant>> cir) {
        cir.setReturnValue(LongwingsVariantPerf.cachedAllVariantPool(butterflyVariants, mothVariants));
    }
}
