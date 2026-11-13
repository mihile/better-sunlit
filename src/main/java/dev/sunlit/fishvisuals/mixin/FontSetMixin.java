package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.font.GlyphInfo;
import dev.sunlit.bettersunlit.client.FontControls;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(FontSet.class)
public abstract class FontSetMixin {
    @Inject(method = "m_243128_(IZ)Lcom/mojang/blaze3d/font/GlyphInfo;", at = @At("HEAD"), cancellable = true, remap = false)
    private void sunlit$hideControlGlyphInfo(int codePoint, boolean filterFishy, CallbackInfoReturnable<GlyphInfo> cir) {
        if (FontControls.isInvisible(codePoint)) {
            cir.setReturnValue(FontControls.ZERO_WIDTH);
        }
    }

    @Inject(method = "m_95078_(I)Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;", at = @At("HEAD"), cancellable = true, remap = false)
    private void sunlit$hideControlBakedGlyph(int codePoint, CallbackInfoReturnable<BakedGlyph> cir) {
        if (FontControls.isInvisible(codePoint)) {
            cir.setReturnValue(EmptyGlyph.f_232594_);
        }
    }
}
