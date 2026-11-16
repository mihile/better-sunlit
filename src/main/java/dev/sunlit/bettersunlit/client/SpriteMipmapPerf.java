package dev.sunlit.bettersunlit.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.sunlit.fishvisuals.mixin.PerfNativeImageAccessor;
import me.jellysquid.mods.sodium.client.util.color.ColorSRGB;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraftforge.client.ForgeHooksClient;
import org.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

/**
 * Parallelizes per-sprite mipmap generation and accelerates mipmap downsampling and Embeddium
 * sprite transparency scans via bulk row-major off-heap transfers and early exits.
 */
public final class SpriteMipmapPerf {
    private static final ThreadLocal<int[]> TL_SRC = ThreadLocal.withInitial(() -> new int[4096]);
    private static final ThreadLocal<int[]> TL_DST = ThreadLocal.withInitial(() -> new int[1024]);

    private SpriteMipmapPerf() {
    }

    private static int[] borrowIntBuffer(ThreadLocal<int[]> tl, int required) {
        int[] buf = tl.get();
        if (buf.length < required) {
            buf = new int[required];
            tl.set(buf);
        }
        return buf;
    }

    public static NativeImage[] generateMipLevelsFast(NativeImage[] images, int mipmapLevels) {
        if (images == null || images.length == 0 || mipmapLevels + 1 <= images.length) {
            return images;
        }
        NativeImage base = images[0];
        if (base == null || base.m_85102_() != NativeImage.Format.RGBA) {
            return null;
        }
        long basePtr = ((PerfNativeImageAccessor) (Object) base).sunlit$getPixels();
        if (basePtr == 0L) {
            return null;
        }

        NativeImage[] out = new NativeImage[mipmapLevels + 1];
        out[0] = base;
        int maxMip = ForgeHooksClient.getMaxMipmapLevel(base.m_84982_(), base.m_85084_());
        // Preserve Forge's handling of custom supplied levels, unusual dimensions and excessive
        // level counts by leaving those cases to the installed implementation.
        if (images.length != 1 || mipmapLevels < 0 || mipmapLevels > maxMip) {
            return null;
        }

        int[] srcBuf = null;
        int[] dstBuf = null;
        int prevW = base.m_84982_();
        int prevH = base.m_85084_();
        boolean srcValid = false;

        for (int level = 1; level <= mipmapLevels; level++) {
            if (level < images.length) {
                NativeImage provided = images[level];
                out[level] = provided;
                if (provided != null) {
                    prevW = provided.m_84982_();
                    prevH = provided.m_85084_();
                }
                srcValid = false;
                continue;
            }

            NativeImage prev = out[level - 1];
            int dstW = Math.max(1, prevW >> 1);
            int dstH = Math.max(1, prevH >> 1);
            NativeImage next = new NativeImage(dstW, dstH, false);
            out[level] = next;

            if (level <= maxMip && prevW >= (dstW << 1) && prevH >= (dstH << 1)) {
                if (prev.m_85102_() != NativeImage.Format.RGBA || next.m_85102_() != NativeImage.Format.RGBA) {
                    closeAllocated(out, images.length, level);
                    return null;
                }
                long dstPtr = ((PerfNativeImageAccessor) (Object) next).sunlit$getPixels();
                if (dstPtr == 0L) {
                    closeAllocated(out, images.length, level);
                    return null;
                }
                if (!srcValid) {
                    long srcPtr = ((PerfNativeImageAccessor) (Object) prev).sunlit$getPixels();
                    if (srcPtr == 0L) {
                        closeAllocated(out, images.length, level);
                        return null;
                    }
                    int srcCount = prevW * prevH;
                    srcBuf = borrowIntBuffer(TL_SRC, srcCount);
                    MemoryUtil.memIntBuffer(srcPtr, srcCount).get(srcBuf, 0, srcCount);
                }
                int dstCount = dstW * dstH;
                dstBuf = borrowIntBuffer(TL_DST, dstCount);
                downsampleQuad(srcBuf, prevW, dstBuf, dstW, dstH);
                MemoryUtil.memIntBuffer(dstPtr, dstCount).put(dstBuf, 0, dstCount);
                // Reuse the newly written mip buffer directly as source for the next level.
                int[] swap = srcBuf;
                srcBuf = dstBuf;
                dstBuf = swap;
                srcValid = true;
            } else {
                srcValid = false;
            }
            prevW = dstW;
            prevH = dstH;
        }
        return out;
    }

    private static void closeAllocated(NativeImage[] out, int preservedCount, int lastAllocated) {
        for (int i = preservedCount; i <= lastAllocated; i++) {
            if (out[i] != null) {
                out[i].close();
            }
        }
    }

    private static void downsampleQuad(int[] src, int srcW, int[] dst, int dstW, int dstH) {
        for (int y = 0; y < dstH; y++) {
            int row0 = (y << 1) * srcW;
            int row1 = row0 + srcW;
            int dstRow = y * dstW;
            for (int x = 0; x < dstW; x++) {
                int sx = x << 1;
                int c0 = src[row0 + sx];
                int c1 = src[row0 + sx + 1];
                int c2 = src[row1 + sx];
                int c3 = src[row1 + sx + 1];
                if (c0 == c1 && c2 == c3 && c0 == c2) {
                    dst[dstRow + x] = c0;
                } else {
                    dst[dstRow + x] = weightedAverageColor(
                            weightedAverageColor(c0, c1),
                            weightedAverageColor(c2, c3));
                }
            }
        }
    }

    public static int weightedAverageColor(int one, int two) {
        int a1 = FastColor.ABGR32.m_266503_(one);
        int a2 = FastColor.ABGR32.m_266503_(two);
        if (a1 == a2) {
            float r = (ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266313_(one))
                    + ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266313_(two))) * 0.5F;
            float g = (ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266446_(one))
                    + ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266446_(two))) * 0.5F;
            float b = (ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266247_(one))
                    + ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266247_(two))) * 0.5F;
            return ColorSRGB.linearToSrgb(r, g, b, a1);
        }
        if (a1 == 0) {
            return (two & 0x00FFFFFF) | ((a2 >> 2) << 24);
        }
        if (a2 == 0) {
            return (one & 0x00FFFFFF) | ((a1 >> 2) << 24);
        }
        float scale = 1.0F / (a1 + a2);
        float w1 = a1 * scale;
        float w2 = a2 * scale;
        float r = ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266313_(one)) * w1
                + ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266313_(two)) * w2;
        float g = ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266446_(one)) * w1
                + ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266446_(two)) * w2;
        float b = ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266247_(one)) * w1
                + ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266247_(two)) * w2;
        return ColorSRGB.linearToSrgb(r, g, b, (a1 + a2) >> 1);
    }

    public static SpriteTransparencyLevel processTransparentFast(NativeImage image, boolean fillInTransparent) {
        if (image == null || image.m_85102_() != NativeImage.Format.RGBA) {
            return null;
        }
        long ptr = ((PerfNativeImageAccessor) (Object) image).sunlit$getPixels();
        if (ptr == 0L) {
            return null;
        }
        int totalPixels = image.m_85084_() * image.m_84982_();
        if (totalPixels <= 0) {
            return SpriteTransparencyLevel.OPAQUE;
        }
        int[] px = borrowIntBuffer(TL_SRC, totalPixels);
        MemoryUtil.memIntBuffer(ptr, totalPixels).get(px, 0, totalPixels);
        int result = processTransparentPixels(px, totalPixels, fillInTransparent);
        if ((result & 4) != 0) {
            MemoryUtil.memIntBuffer(ptr, totalPixels).put(px, 0, totalPixels);
        }
        return switch (result & 3) {
            case 0 -> SpriteTransparencyLevel.OPAQUE;
            case 1 -> SpriteTransparencyLevel.TRANSPARENT;
            default -> SpriteTransparencyLevel.TRANSLUCENT;
        };
    }

    /** Returns transparency ordinal, with bit 2 set when transparent pixels were filled. */
    public static int processTransparentPixels(int[] px, int totalPixels, boolean fillInTransparent) {
        boolean sawZeroAlpha = false;
        boolean sawTranslucent = false;

        if (!fillInTransparent) {
            for (int i = 0; i < totalPixels; i++) {
                int a = FastColor.ABGR32.m_266503_(px[i]);
                if (a == 0) {
                    sawZeroAlpha = true;
                } else if (a < 255) {
                    return 2;
                }
            }
            return sawZeroAlpha ? 1 : 0;
        }

        float rSum = 0.0F;
        float gSum = 0.0F;
        float bSum = 0.0F;
        float totalWeight = 0.0F;

        for (int i = 0; i < totalPixels; i++) {
            int color = px[i];
            int a = FastColor.ABGR32.m_266503_(color);
            if (a > 0) {
                if (a < 255) {
                    sawTranslucent = true;
                }
                float w = a;
                rSum += ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266313_(color)) * w;
                gSum += ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266446_(color)) * w;
                bSum += ColorSRGB.srgbToLinear(FastColor.ABGR32.m_266247_(color)) * w;
                totalWeight += w;
            } else {
                sawZeroAlpha = true;
            }
        }

        int level = sawTranslucent ? 2 : (sawZeroAlpha ? 1 : 0);

        if (sawZeroAlpha && totalWeight > 0.0F) {
            int fillColor = ColorSRGB.linearToSrgb(
                    rSum / totalWeight,
                    gSum / totalWeight,
                    bSum / totalWeight,
                    0);
            for (int i = 0; i < totalPixels; i++) {
                if (FastColor.ABGR32.m_266503_(px[i]) == 0) {
                    px[i] = fillColor;
                }
            }
            return level | 4;
        }
        return level;
    }

    public static void generateMipLevelsParallel(Map<ResourceLocation, TextureAtlasSprite> sprites, int mipLevel) {
        if (sprites == null || sprites.isEmpty() || mipLevel <= 0) {
            return;
        }
        int count = sprites.size();
        if (count < 32) {
            for (TextureAtlasSprite sprite : sprites.values()) {
                sprite.m_245424_().m_246368_(mipLevel);
            }
            return;
        }
        TextureAtlasSprite[] arr = sprites.values().toArray(new TextureAtlasSprite[0]);
        int workers = Math.min(Math.max(2, Runtime.getRuntime().availableProcessors()), 12);
        int batch = Math.max(16, (arr.length + workers - 1) / workers);
        List<ForkJoinTask<?>> tasks = new ArrayList<>(workers);
        for (int start = batch; start < arr.length; start += batch) {
            final int s = start;
            final int e = Math.min(arr.length, start + batch);
            tasks.add(ForkJoinPool.commonPool().submit(() -> {
                for (int i = s; i < e; i++) {
                    arr[i].m_245424_().m_246368_(mipLevel);
                }
            }));
        }
        int firstEnd = Math.min(arr.length, batch);
        for (int i = 0; i < firstEnd; i++) {
            arr[i].m_245424_().m_246368_(mipLevel);
        }
        for (ForkJoinTask<?> task : tasks) {
            task.join();
        }
    }
}
