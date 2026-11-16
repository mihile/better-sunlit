package dev.sunlit.fishvisuals.mixin;

import de.keksuccino.fancymenu.util.mcef.MCEFUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caches {@link MCEFUtil#isMCEFLoaded()} so repeated FancyMenu layout queries do not repeatedly
 * invoke {@code Class.forName("com.cinemamod.mcef.MCEF", ...)} and catch exceptions.
 */
@Mixin(value = MCEFUtil.class, remap = false)
public abstract class PerfMcefUtilMixin {
    @Shadow
    public static volatile boolean MCEF_critical_failure;

    @Unique
    private static volatile Boolean sunlit$cachedMcefLoaded;

    @Inject(method = "isMCEFLoaded", at = @At("HEAD"), cancellable = true)
    private static void sunlit$fastCachedMcefCheck(CallbackInfoReturnable<Boolean> cir) {
        if (MCEF_critical_failure) {
            cir.setReturnValue(Boolean.FALSE);
            return;
        }
        Boolean cached = sunlit$cachedMcefLoaded;
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "isMCEFLoaded", at = @At("RETURN"))
    private static void sunlit$storeMcefCheck(CallbackInfoReturnable<Boolean> cir) {
        if (!MCEF_critical_failure && sunlit$cachedMcefLoaded == null) {
            sunlit$cachedMcefLoaded = Boolean.valueOf(cir.getReturnValueZ());
        }
    }
}
