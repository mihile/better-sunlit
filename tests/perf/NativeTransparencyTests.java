package dev.sunlit.bettersunlit.tests.perf;

import com.mojang.blaze3d.platform.NativeImage;
import dev.sunlit.bettersunlit.client.SpriteMipmapPerf;
import java.util.Random;
import org.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel;

/** Compares the optimized native buffers against the installed Emberbium method. */
public final class NativeTransparencyTests {
    public static void main(String[] args) throws Exception {
        Class<?> mixin = Class.forName("me.jellysquid.mods.sodium.mixin.features.textures.mipmaps.SpriteContentsMixin");
        var method = mixin.getDeclaredMethod("embeddium$processTransparentImages", NativeImage.class, boolean.class);
        method.setAccessible(true);
        var levelMethod = mixin.getMethod("embeddium$getTransparencyLevel");
        Random rng = new Random(0xBA5E123L);
        int cases = 0;
        for (int width : new int[]{1, 2, 4, 8, 16, 32}) for (int height : new int[]{1, 2, 4, 8, 16, 32}) {
            for (int sample = 0; sample < 8; sample++) for (boolean fill : new boolean[]{false, true}) {
                try (var oldImage = new NativeImage(width, height, false); var fastImage = new NativeImage(width, height, false)) {
                    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                        int alpha = switch (rng.nextInt(4)) { case 0 -> 0; case 1 -> 255; default -> rng.nextInt(256); };
                        int color = rng.nextInt() & 0xffffff | alpha << 24;
                        oldImage.m_84988_(x, y, color);
                        fastImage.m_84988_(x, y, color);
                    }
                    Object instance = mixin.getConstructor().newInstance();
                    method.invoke(instance, oldImage, fill);
                    SpriteTransparencyLevel oldLevel = (SpriteTransparencyLevel) levelMethod.invoke(instance);
                    SpriteTransparencyLevel fastLevel = SpriteMipmapPerf.processTransparentFast(fastImage, fill);
                    if (oldLevel != fastLevel) throw new AssertionError("Sprite transparency differs");
                    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                        if (oldImage.m_84985_(x, y) != fastImage.m_84985_(x, y))
                            throw new AssertionError("Sprite pixels differ for " + width + "x" + height + " fill=" + fill);
                    }
                    cases++;
                }
            }
        }
        System.out.println("PASS: " + cases + " native-image transparency comparisons against installed Emberbium");
    }
}
