package dev.sunlit.bettersunlit.tests.perf;

import com.mojang.blaze3d.platform.NativeImage;
import dev.sunlit.bettersunlit.client.SpriteMipmapPerf;
import java.util.Random;
import net.minecraft.client.renderer.texture.MipmapGenerator;

/** Exercises the native image buffers against the installed Iris color blend. */
public final class NativeMipmapTests {
    public static void main(String[] args) {
        Random random = new Random(0xC0110L);
        int cases = 0;
        for (int width : new int[]{2, 4, 8, 16, 32}) {
            for (int height : new int[]{2, 4, 8, 16, 32}) {
                int maxLevels = Math.min(Integer.numberOfTrailingZeros(width), Integer.numberOfTrailingZeros(height));
                for (int sample = 0; sample < 8; sample++) {
                    var left = new NativeImage(width, height, false);
                    var right = new NativeImage(width, height, false);
                    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                        int color = random.nextInt();
                        left.m_84988_(x, y, color);
                        right.m_84988_(x, y, color);
                    }
                    NativeImage[] expected = null, actual = null;
                    try {
                        expected = MipmapGenerator.m_246246_(new NativeImage[]{left}, maxLevels);
                        actual = SpriteMipmapPerf.generateMipLevelsFast(new NativeImage[]{right}, maxLevels);
                        if (actual == null || actual.length != expected.length) throw new AssertionError("Missing mip levels");
                        for (int level = 0; level < actual.length; level++) {
                            NativeImage a = actual[level], e = expected[level];
                            if (a.m_84982_() != e.m_84982_() || a.m_85084_() != e.m_85084_())
                                throw new AssertionError("Mipmap dimensions differ");
                            for (int y = 0; y < a.m_85084_(); y++) for (int x = 0; x < a.m_84982_(); x++) {
                                if (a.m_84985_(x, y) != e.m_84985_(x, y))
                                    throw new AssertionError("Mipmap pixel differs at " + width + "x" + height + " level " + level);
                            }
                        }
                        cases++;
                    } finally {
                        if (expected == null) left.close(); else for (NativeImage image : expected) image.close();
                        if (actual == null) right.close(); else for (NativeImage image : actual) image.close();
                    }
                }
            }
        }
        try (var image = new NativeImage(4, 4, false); var supplied = new NativeImage(2, 2, false)) {
            if (SpriteMipmapPerf.generateMipLevelsFast(new NativeImage[]{image, supplied}, 3) != null)
                throw new AssertionError("Custom levels must use the original generator");
            if (SpriteMipmapPerf.generateMipLevelsFast(new NativeImage[]{image}, 3) != null)
                throw new AssertionError("Excessive levels must use the original generator");
        }
        System.out.println("PASS: " + cases + " native-image mipmap comparisons against the installed Iris blend");
    }
}
