package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.font.GlyphInfo;
import dev.sunlit.bettersunlit.client.FontControls;
import dev.sunlit.bettersunlit.client.ModernUiEmojiCompat;
import icyllis.modernui.graphics.text.FontFamily;
import icyllis.modernui.graphics.text.FontPaint;
import icyllis.modernui.mc.text.StandardFontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Pseudo
@Mixin(targets = "icyllis.modernui.mc.text.StandardFontSet", remap = false)
public abstract class StandardFontSetMixin {
    @Shadow(remap = false) private List<FontFamily> mFamilies;
    @Shadow(remap = false) @Final private FontPaint mStandardPaint;
    @Shadow(remap = false) private float mResLevel;

    @Inject(method = "m_243128_(IZ)Lcom/mojang/blaze3d/font/GlyphInfo;", at = @At("HEAD"), cancellable = true, remap = false)
    private void sunlit$hideControlGlyphInfo(int codePoint, boolean filterFishy, CallbackInfoReturnable<GlyphInfo> cir) {
        if (FontControls.isInvisible(codePoint)) cir.setReturnValue(FontControls.ZERO_WIDTH);
    }

    @Inject(method = "m_95078_(I)Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;", at = @At("HEAD"), cancellable = true, remap = false)
    private void sunlit$hideControlBakedGlyph(int codePoint, CallbackInfoReturnable<BakedGlyph> cir) {
        if (FontControls.isInvisible(codePoint)) cir.setReturnValue(EmptyGlyph.f_232594_);
    }

    @Inject(method = "cacheGlyphInfo(I)Lcom/mojang/blaze3d/font/GlyphInfo;", at = @At("HEAD"), cancellable = true, remap = false)
    private void sunlit$handleEmojiGlyphInfo(int codePoint, CallbackInfoReturnable<GlyphInfo> cir) {
        if (this.mResLevel <= 0.0F) return;
        var glyph = ModernUiEmojiCompat.resolve(this.mFamilies, this.mStandardPaint, codePoint);
        if (glyph != null) {
            cir.setReturnValue(new StandardFontSet.StandardGlyphInfo(glyph.advance() / this.mResLevel));
        }
    }

    @Inject(method = "cacheGlyph(I)Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;", at = @At("HEAD"), cancellable = true, remap = false)
    private void sunlit$handleEmojiBakedGlyph(int codePoint, CallbackInfoReturnable<BakedGlyph> cir) {
        var glyph = ModernUiEmojiCompat.resolve(this.mFamilies, this.mStandardPaint, codePoint);
        if (glyph != null) {
            BakedGlyph baked = ModernUiEmojiCompat.bake(glyph, this.mStandardPaint);
            if (baked != null) cir.setReturnValue(baked);
        }
    }
}
