package dev.sunlit.bettersunlit.client;

import icyllis.modernui.graphics.text.EmojiFont;
import icyllis.modernui.graphics.text.Font;
import icyllis.modernui.graphics.text.FontFamily;
import icyllis.modernui.graphics.text.FontPaint;
import icyllis.modernui.graphics.text.OutlineFont;
import icyllis.modernui.mc.text.BitmapFont;
import icyllis.modernui.mc.text.GLBakedGlyph;
import icyllis.modernui.mc.text.GlyphManager;
import icyllis.modernui.mc.text.SpaceFont;
import icyllis.modernui.mc.text.TextLayoutProcessor;
import icyllis.modernui.mc.text.TextRenderType;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** EmojiFont bridge for ModernUI 3.12's StandardFontSet, used by vanilla-style renderers. */
public final class ModernUiEmojiCompat {
    private ModernUiEmojiCompat() {}

    public record EmojiGlyph(EmojiFont font, int glyphId, float advance) {}

    public static EmojiGlyph resolve(List<FontFamily> families, FontPaint paint, int codePoint) {
        if (families == null || !Character.isValidCodePoint(codePoint)) return null;
        for (FontFamily family : families) {
            if (!family.hasGlyph(codePoint)) continue;
            Font font = family.getClosestMatch(0);
            if (font instanceof EmojiFont emoji) {
                char[] chars = Character.toChars(codePoint);
                IntArrayList glyphs = new IntArrayList(1);
                float advance = emoji.doComplexLayout(chars, 0, chars.length, 0, chars.length,
                        false, paint, glyphs, null, null, 0, null, 0.0F, 0.0F);
                if (glyphs.size() == 1 && glyphs.getInt(0) != 0 && advance > 0.0F) {
                    return new EmojiGlyph(emoji, glyphs.getInt(0), advance);
                }
            } else if (font instanceof BitmapFont bitmap) {
                if (bitmap.getGlyph(codePoint) != null) return null;
            } else if (font instanceof SpaceFont space) {
                // Zero and negative advances are intentional in many resource packs.
                if (!Float.isNaN(space.getAdvance(codePoint))) return null;
            } else if (font instanceof OutlineFont outline) {
                if (outline.hasGlyph(codePoint, 0)) return null;
            } else {
                // An unfamiliar provider belongs to the original renderer.
                return null;
            }
        }
        return null;
    }

    public static BakedGlyph bake(EmojiGlyph emoji, FontPaint paint) {
        GLBakedGlyph baked = GlyphManager.getInstance().lookupGlyph(
                emoji.font(), (int) paint.getFontSize(), emoji.glyphId());
        // Let the normal renderer try fallback fonts if the emoji image is unavailable.
        // Returning EmptyGlyph here permanently hid a character with nonzero advance.
        return baked == null ? null : new EmojiBakedGlyph(baked);
    }

    private static final class EmojiBakedGlyph extends BakedGlyph {
        private static final GlyphRenderTypes EMPTY_TYPES =
                GlyphRenderTypes.m_284354_(new ResourceLocation(""));

        EmojiBakedGlyph(GLBakedGlyph glyph) {
            super(EMPTY_TYPES, glyph.u1, glyph.u2, glyph.v1, glyph.v2,
                    1.0F + glyph.x * scale(), 1.0F + (glyph.x + glyph.width) * scale(),
                    10.0F + glyph.y * scale(), 10.0F + (glyph.y + glyph.height) * scale());
            // ModernUI invalidates StandardFontSet caches when its atlas resizes/reloads.
        }

        private static float scale() { return TextLayoutProcessor.sBaseFontSize / 64.0F; }

        @Override
        public RenderType m_181387_(DisplayMode mode) {
            return TextRenderType.getOrCreate(GlyphManager.getInstance().getEmojiTexture(), mode, true);
        }
    }
}
