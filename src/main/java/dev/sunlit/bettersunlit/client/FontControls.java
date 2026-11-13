package dev.sunlit.bettersunlit.client;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;

import java.util.function.Function;

/** Controls reaching Emojiful's codepoint renderer must not draw missing-glyph boxes. */
public final class FontControls {
    public static final GlyphInfo ZERO_WIDTH = new InvisibleGlyph();

    private FontControls() {}

    public static boolean isInvisible(int codePoint) {
        // Deliberately narrow: preserve resource-pack icons, spaces, and emoji components.
        return codePoint == '\n' || codePoint == '\r' || codePoint == 0x200B || codePoint == 0xFEFF;
    }

    private static final class InvisibleGlyph implements GlyphInfo {
        @Override
        public float m_7403_() { return 0.0F; }

        @Override
        public float m_5619_() { return 0.0F; } // Bold must not add one pixel per control.

        @Override
        public float m_5645_() { return 0.0F; }

        @Override
        public BakedGlyph m_213604_(Function<SheetGlyphInfo, BakedGlyph> baker) {
            return EmptyGlyph.f_232594_;
        }
    }
}
