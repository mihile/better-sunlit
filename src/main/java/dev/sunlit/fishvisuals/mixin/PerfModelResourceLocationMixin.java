package dev.sunlit.fishvisuals.mixin;

import net.minecraft.client.resources.model.ModelResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

/**
 * Avoids {@link String#toLowerCase(Locale)} array/coder work in {@link ModelResourceLocation}
 * when the variant string is already lowercase ASCII (the common case for blockstate variants).
 */
@Mixin(value = ModelResourceLocation.class, remap = false)
public abstract class PerfModelResourceLocationMixin {
    @Inject(method = "m_246655_", at = @At("HEAD"), cancellable = true)
    private static void betterSunlit$fastVariantLower(String variant, CallbackInfoReturnable<String> cir) {
        int len = variant.length();
        for (int i = 0; i < len; i++) {
            char c = variant.charAt(i);
            if ((c >= 'A' && c <= 'Z') || c >= 0x80) {
                return;
            }
        }
        cir.setReturnValue(variant);
    }
}
