import dev.sunlit.bettersunlit.client.FontControls;
import dev.sunlit.bettersunlit.client.ModernUiEmojiCompat;
import dev.sunlit.bettersunlit.client.ModernUiPackFonts;
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

        // A catch-all missing provider must never displace bitmap icons or zero-width spaces.
        com.mojang.blaze3d.font.GlyphProvider icons = new com.mojang.blaze3d.font.GlyphProvider() {
            public it.unimi.dsi.fastutil.ints.IntSet m_6990_() {
                return new IntOpenHashSet(new int[]{0xEA01, 0x200C});
            }
            public com.mojang.blaze3d.font.GlyphInfo m_214022_(int cp) {
                return cp == 0xEA01 || cp == 0x200C ? FontControls.ZERO_WIDTH : null;
            }
        };
        com.mojang.blaze3d.font.GlyphProvider missing = new com.mojang.blaze3d.font.GlyphProvider() {
            public it.unimi.dsi.fastutil.ints.IntSet m_6990_() {
                return it.unimi.dsi.fastutil.ints.IntSets.EMPTY_SET;
            }
            public com.mojang.blaze3d.font.GlyphInfo m_214022_(int cp) {
                return net.minecraft.client.gui.font.glyphs.SpecialGlyphs.MISSING;
            }
        };
        var retained = new java.util.ArrayList<com.mojang.blaze3d.font.GlyphProvider>();
        var widths = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.IntList>();
        dev.sunlit.bettersunlit.client.FontSetPerf.reloadProviders(List.of(icons, missing), widths, retained);
        check(retained.equals(List.of(icons)), "bitmap and spacing providers survive missing fallback");
        check(widths.get(0).contains(0xEA01) && widths.get(0).contains(0x200C), "icon and spacing widths retained");

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
        testPackFontOverrides();
        System.out.println("PASS: " + checks + " font compatibility checks; CPU layout only, no game/GL launched.");
    }

    private static void testPackFontOverrides() {
        var bitmap = new net.minecraft.resources.ResourceLocation("minecraft", "font/nonlatin_european.png");
        var vanilla = texture("vanilla", new byte[]{1, 2});
        var identical = texture("file/duplicate.zip", new byte[]{1, 2});
        var replacement = texture("file/sunlit_overrides.zip", new byte[]{1, 3});
        check(!ModernUiPackFonts.hasPackOverride(resources(List.of(vanilla)), bitmap), "vanilla font remains filtered");
        check(!ModernUiPackFonts.hasPackOverride(resources(List.of(vanilla, identical)), bitmap), "identical copy is not an override");
        check(ModernUiPackFonts.hasPackOverride(resources(List.of(vanilla, replacement)), bitmap), "active replacement survives");
        check(!ModernUiPackFonts.hasPackOverride(resources(List.of(vanilla, replacement, identical)), bitmap),
                "only the highest priority resource is considered");

        var modifiedFont = (icyllis.modernui.graphics.text.Font) java.lang.reflect.Proxy.newProxyInstance(
                FontCompatTests.class.getClassLoader(),
                new Class<?>[]{icyllis.modernui.graphics.text.Font.class, ModernUiPackFonts.BitmapOverride.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getStyle" -> 0;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "sunlit$isPackOverride" -> true;
                    default -> throw new AssertionError(method);
                });
        var custom = new FontFamily(modifiedFont);
        var first = emoji(0x1F600, true);
        var last = emoji(0x1F514, true);
        var excluded = emoji(0x23F3, true);
        var selected = new java.util.LinkedHashSet<>(List.of(first, last));
        ModernUiPackFonts.keepPackOverrides(selected, List.of(first, custom, excluded, last));
        check(List.copyOf(selected).equals(List.of(first, custom, last)), "pack glyphs keep resource order and stock exclusions");
        ModernUiPackFonts.keepPackOverrides(selected, List.of(first, custom, excluded, last));
        check(List.copyOf(selected).equals(List.of(first, custom, last)), "reloading never duplicates a provider");
    }

    private static net.minecraft.server.packs.resources.Resource texture(String packId, byte[] bytes) {
        var pack = (net.minecraft.server.packs.PackResources) java.lang.reflect.Proxy.newProxyInstance(
                FontCompatTests.class.getClassLoader(), new Class<?>[]{net.minecraft.server.packs.PackResources.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("m_5542_")) throw new AssertionError(method);
                    return packId;
                });
        return new net.minecraft.server.packs.resources.Resource(pack, () -> new java.io.ByteArrayInputStream(bytes));
    }

    private static net.minecraft.server.packs.resources.ResourceManager resources(
            List<net.minecraft.server.packs.resources.Resource> stack) {
        return (net.minecraft.server.packs.resources.ResourceManager) java.lang.reflect.Proxy.newProxyInstance(
                FontCompatTests.class.getClassLoader(), new Class<?>[]{net.minecraft.server.packs.resources.ResourceManager.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("m_213829_")) throw new AssertionError(method);
                    return stack;
                });
    }
}
