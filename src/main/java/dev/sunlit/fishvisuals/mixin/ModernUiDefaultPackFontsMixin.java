package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ModernUiPackFonts;
import icyllis.modernui.graphics.text.FontCollection;
import icyllis.modernui.graphics.text.FontFamily;
import icyllis.modernui.mc.text.TextLayoutEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Pseudo
@Mixin(targets = "icyllis.modernui.mc.text.TextLayoutEngine", remap = false)
public abstract class ModernUiDefaultPackFontsMixin {
    @Shadow(remap = false)
    private FontCollection mRawDefaultFontCollection;

    @Inject(method = "populateDefaultFonts(Ljava/util/Set;I)V", at = @At("RETURN"), remap = false)
    private void sunlit$keepPackBitmaps(Set<FontFamily> families, int behavior, CallbackInfo ci) {
        if (behavior == TextLayoutEngine.DEFAULT_FONT_BEHAVIOR_ONLY_EXCLUDE && mRawDefaultFontCollection != null) {
            ModernUiPackFonts.keepPackOverrides(families, mRawDefaultFontCollection.getFamilies());
        }
    }
}
