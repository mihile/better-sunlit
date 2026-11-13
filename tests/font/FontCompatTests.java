import dev.sunlit.bettersunlit.client.FontControls;
import dev.sunlit.bettersunlit.client.ModernUiEmojiCompat;
import icyllis.modernui.graphics.text.EmojiFont;
import icyllis.modernui.graphics.text.FontFamily;
import icyllis.modernui.graphics.text.FontPaint;
import icyllis.modernui.mc.text.SpaceFont;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.List;

/** Exercises installed ModernUI's real CPU layout; never creates a game or GL context. */
public class FontCompatTests {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static FontFamily emoji(int codePoint, boolean mapped) {
        var mapping = new Object2IntOpenHashMap<CharSequence>();
        if (mapped) mapping.put(new String(Character.toChars(codePoint)), 1);
        return new FontFamily(new EmojiFont("test-" + codePoint + "-" + mapped,
                new IntOpenHashSet(new int[]{codePoint}), 72, 56, 8, 8,
                mapping, List.of("unused.png")));
    }

    public static void main(String[] args) throws Exception {
        for (int cp : new int[]{10, 13, 0x200B, 0xFEFF}) {
            check(FontControls.isInvisible(cp), "control U+" + Integer.toHexString(cp));
        }
        for (int cp : new int[]{'A', 0xD55C, ' ', 0xE001, 0x1F600, 0x200D, 0xFE0F}) {
            check(!FontControls.isInvisible(cp), "preserve printable/emoji U+" + Integer.toHexString(cp));
        }
        check(FontControls.ZERO_WIDTH.m_83827_(false) == 0, "normal control advance");
        check(FontControls.ZERO_WIDTH.m_83827_(true) == 0, "bold control advance");
        check(FontControls.ZERO_WIDTH.m_5645_() == 0, "control shadow offset");

        var paint = new FontPaint();
        paint.setFontSize(16);
        var smile = emoji(0x1F600, true);
        var result = ModernUiEmojiCompat.resolve(List.of(smile), paint, 0x1F600);
        check(result != null && result.glyphId() == 1 && result.advance() > 0, "supplementary emoji");
        var sun = emoji(0x2600, true);
        check(ModernUiEmojiCompat.resolve(List.of(sun), paint, 0x2600) != null, "BMP symbol emoji");
        check(ModernUiEmojiCompat.resolve(List.of(sun), paint, 0xD55C) == null, "Korean remains native");
        check(ModernUiEmojiCompat.resolve(null, paint, 'A') == null, "unloaded font list");
        check(ModernUiEmojiCompat.resolve(List.of(smile), paint, -1) == null, "invalid negative codepoint");
        check(ModernUiEmojiCompat.resolve(List.of(smile), paint, 0x110000) == null, "invalid large codepoint");
        check(ModernUiEmojiCompat.resolve(List.of(emoji(0x1F600, false)), paint, 0x1F600) == null,
                "coverage without a mapped emoji is not renderable");
        check(ModernUiEmojiCompat.resolve(List.of(emoji(0x1F600, false), smile), paint, 0x1F600) != null,
                "continue after an unrenderable emoji family");

        var emojiA = emoji('A', true);
        check(ModernUiEmojiCompat.resolve(List.of(FontFamily.SANS_SERIF, emojiA), paint, 'A') == null,
                "earlier outline font wins");
        check(ModernUiEmojiCompat.resolve(List.of(emojiA, FontFamily.SANS_SERIF), paint, 'A') != null,
                "earlier emoji font wins");
        var ctor = SpaceFont.class.getDeclaredConstructor(String.class, Int2FloatOpenHashMap.class);
        ctor.setAccessible(true);
        for (float width : new float[]{0, -4, 8}) {
            var advances = new Int2FloatOpenHashMap();
            advances.defaultReturnValue(Float.NaN);
            advances.put(0x1F600, width);
            var spacing = new FontFamily(ctor.newInstance("test-space", advances));
            check(ModernUiEmojiCompat.resolve(List.of(spacing, smile), paint, 0x1F600) == null,
                    "resource-pack space advance wins: " + width);
        }
        System.out.println("PASS: " + checks + " font compatibility checks; CPU layout only, no game/GL launched.");
    }
}
