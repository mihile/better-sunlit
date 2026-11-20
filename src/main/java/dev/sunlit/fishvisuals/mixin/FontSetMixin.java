package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import dev.sunlit.bettersunlit.client.FontControls;
import dev.sunlit.bettersunlit.client.FontSetPerf;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.gui.font.CodepointMap;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;
import net.minecraft.client.gui.font.glyphs.SpecialGlyphs;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(FontSet.class)
public abstract class FontSetMixin {
    @Shadow
    private BakedGlyph f_95053_;

    @Shadow
    private BakedGlyph f_95054_;

    @Shadow
    @Final
    private List<GlyphProvider> f_95055_;

    @Shadow
    @Final
    private CodepointMap<BakedGlyph> f_95056_;

    @Shadow
    @Final
    private CodepointMap<?> f_95057_;

    @Shadow
    @Final
    private Int2ObjectMap<IntList> f_95058_;

    @Shadow
    protected abstract void m_95077_();

    @Shadow
    protected abstract void m_95080_();

    @Shadow
    protected abstract BakedGlyph m_232556_(SheetGlyphInfo glyphInfo);

    @Inject(method = "m_95071_(Ljava/util/List;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void sunlit$skipLazyUnihexReload(List<GlyphProvider> providers, CallbackInfo ci) {
        ci.cancel();
        this.m_95077_();
        this.m_95080_();
        this.f_95056_.m_284192_();
        this.f_95057_.m_284192_();
        this.f_95058_.clear();
        this.f_95053_ = SpecialGlyphs.MISSING.m_213604_(this::m_232556_);
        this.f_95054_ = SpecialGlyphs.WHITE.m_213604_(this::m_232556_);
        FontSetPerf.reloadProviders(providers, this.f_95058_, this.f_95055_);
    }
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
