package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ClientLocalizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.github.spacemex.skillsexpnotifier.client.XpToast", remap = false)
public abstract class XpToastMixin {
    @Inject(method = "formatCategoryName(Ljava/lang/String;)Ljava/lang/String;",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void sunlit$localizeCategoryName(String rawKey, CallbackInfoReturnable<String> cir) {
        String localized = ClientLocalizer.localizeSkillCategory(rawKey);
        if (localized != null) {
            cir.setReturnValue(localized);
        }
    }
}
