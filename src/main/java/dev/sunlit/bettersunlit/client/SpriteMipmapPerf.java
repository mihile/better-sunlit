package dev.sunlit.bettersunlit.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.sunlit.fishvisuals.mixin.PerfNativeImageAccessor;
import me.jellysquid.mods.sodium.client.util.color.ColorSRGB;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.ForgeHooksClient;
import org.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Parallelizes per-sprite mipmap generation and accelerates mipmap downsampling and Embeddium
 * sprite transparency scans via bulk row-major off-heap transfers and early exits.
 */
public final class SpriteMipmapPerf {
    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    private static final ThreadLocal<int[]> TL_SRC = ThreadLocal.withInitial(() -> new int[16384]);
    private static final ThreadLocal<int[]> TL_DST = ThreadLocal.withInitial(() -> new int[4096]);
    private static final float[] SRGB_TO_LINEAR = new float[256];
    private static final float[] SRGB_TO_LINEAR_QUARTER = new float[256];
    private static final float[] SRGB_TO_LINEAR_255 = new float[256];

    private static final AtomicBoolean EXTENDED_PREWARMED = new AtomicBoolean(false);

    static {
        for (int i = 0; i < 256; i++) {
            float v = ColorSRGB.srgbToLinear(i);
            SRGB_TO_LINEAR[i] = v;
            SRGB_TO_LINEAR_QUARTER[i] = v * 0.25F;
            SRGB_TO_LINEAR_255[i] = v * 255.0F;
        }
        prewarmOffHeapScans();
    }

    private static void prewarmOffHeapScans() {
        if (!LITTLE_ENDIAN) {
            return;
        }
        long ptr = 0L;
        try {
            ptr = MemoryUtil.nmemAlloc(32L);
            if (ptr == 0L) {
                return;
            }
            for (int i = 0; i < 256; i++) {
                // Pattern 0: all opaque (8 pixels)
                for (int p = 0; p < 8; p++) {
                    MemoryUtil.memPutInt(ptr + ((long) p << 2), 0xFF204060);
                }
                processTransparentOffHeapLE(ptr, 8, false);
                processTransparentOffHeapLE(ptr, 8, true);

                // Pattern 1: cutout with opaque + transparent pixels (7 pixels to exercise odd tail)
                MemoryUtil.memPutInt(ptr, 0);
                MemoryUtil.memPutInt(ptr + 4L, 0xFF334455);
                MemoryUtil.memPutInt(ptr + 8L, 0);
                MemoryUtil.memPutInt(ptr + 12L, 0xFF556677);
                MemoryUtil.memPutInt(ptr + 16L, 0);
                MemoryUtil.memPutInt(ptr + 20L, 0xFF778899);
                MemoryUtil.memPutInt(ptr + 24L, 0);
                processTransparentOffHeapLE(ptr, 7, false);
                processTransparentOffHeapLE(ptr, 7, true);

                // Pattern 2: mixed opaque + translucent + zero alpha (7 pixels)
                MemoryUtil.memPutInt(ptr, 0xFF102030);
                MemoryUtil.memPutInt(ptr + 4L, 0x80405060);
                MemoryUtil.memPutInt(ptr + 8L, 0);
                MemoryUtil.memPutInt(ptr + 12L, 0xFF708090);
                MemoryUtil.memPutInt(ptr + 16L, 0x40A0B0C0);
                MemoryUtil.memPutInt(ptr + 20L, 0);
                MemoryUtil.memPutInt(ptr + 24L, 0xFFD0E0F0);
                processTransparentOffHeapLE(ptr, 7, false);
                processTransparentOffHeapLE(ptr, 7, true);
            }
        } catch (Throwable ignored) {
        } finally {
            if (ptr != 0L) {
                try {
                    MemoryUtil.nmemFree(ptr);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Background Tier-4 JIT warmup across realistic 16x16/32x32 pixel patterns so Graal compiles
     * every fast-path and fallback loop of {@link #isOpaqueFastLE} and
     * {@link #accumulateAndFillLE} before parallel sprite atlas stitching begins.
     */
    public static void prewarmExtendedOffHeap() {
        if (!LITTLE_ENDIAN || !EXTENDED_PREWARMED.compareAndSet(false, true)) {
            return;
        }
        long ptr = 0L;
        try {
            ptr = MemoryUtil.nmemAlloc(4096L);
            if (ptr == 0L) {
                return;
            }
            for (int i = 0; i < 2500; i++) {
                int mode = i & 7;
                if (mode == 0) {
                    // 32x32 (1024 px): fully opaque -> Tier-4 compiles full-quad isOpaqueFastLE(true).
                    for (int p = 0; p < 1024; p++) {
                        MemoryUtil.memPutInt(ptr + ((long) p << 2), 0xFF284868);
                    }
                    processTransparentOffHeapLE(ptr, 1024, false);
                    processTransparentOffHeapLE(ptr, 1024, true);
                } else if (mode == 1) {
                    // 16x16 (256 px): opaque except last pixel -> forces late loop exit.
                    for (int p = 0; p < 255; p++) {
                        MemoryUtil.memPutInt(ptr + ((long) p << 2), 0xFF305070);
                    }
                    MemoryUtil.memPutInt(ptr + (255L << 2), 0x80305070);
                    processTransparentOffHeapLE(ptr, 256, false);
                    processTransparentOffHeapLE(ptr, 256, true);
                } else if (mode == 2 || mode == 3) {
                    // 16x16 (256 px): mix of all-zero quads, all-opaque quads, and translucent pixels.
                    for (int p = 0; p < 256; p++) {
                        int mod = p & 15;
                        int px;
                        if (mod < 4) {
                            px = 0;
                        } else if (mod < 8) {
                            px = 0xFF406080;
                        } else if (mod < 12) {
                            px = (mod & 1) == 0 ? 0xFF203040 : 0x80507090;
                        } else {
                            px = 0xFF708090;
                        }
                        MemoryUtil.memPutInt(ptr + ((long) p << 2), px);
                    }
                    processTransparentOffHeapLE(ptr, 256, true);
                } else if (mode == 4) {
                    // 19 px (4 quads + 3 tail): mix of opaque, zero, and translucent in both quad & tail.
                    for (int p = 0; p < 19; p++) {
                        int px = (p % 3 == 0) ? 0 : ((p % 3 == 1) ? 0xFF335577 : 0x60446688);
                        MemoryUtil.memPutInt(ptr + ((long) p << 2), px);
                    }
                    processTransparentOffHeapLE(ptr, 19, true);
                    processTransparentOffHeapLE(ptr, 19, false);
                } else if (mode == 5) {
                    // 64 px: fully transparent -> totalWeight == 0 early return.
                    for (int p = 0; p < 64; p++) {
                        MemoryUtil.memPutInt(ptr + ((long) p << 2), 0);
                    }
                    processTransparentOffHeapLE(ptr, 64, true);
                    processTransparentOffHeapLE(ptr, 64, false);
                } else {
                    // 17 px: opaque + zero checkerboard hitting fillZeroAlphaLE.
                    for (int p = 0; p < 17; p++) {
                        MemoryUtil.memPutInt(ptr + ((long) p << 2), (p & 1) == 0 ? 0 : 0xFF8899AA);
                    }
                    processTransparentOffHeapLE(ptr, 17, false);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (ptr != 0L) {
                try {
                    MemoryUtil.nmemFree(ptr);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private SpriteMipmapPerf() {
    }

    private static int[] borrowIntBuffer(ThreadLocal<int[]> tl, int required) {
        int[] buf = tl.get();
        if (buf.length < required) {
            buf = new int[required];
            // Never pin >=256 KB (or G1 humongous) scratch arrays permanently across worker threads.
            if (required <= 65536) {
                tl.set(buf);
            }
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
                if (LITTLE_ENDIAN) {
                    long srcPtr = ((PerfNativeImageAccessor) (Object) prev).sunlit$getPixels();
                    if (srcPtr == 0L) {
                        closeAllocated(out, images.length, level);
                        return null;
                    }
                    downsampleQuadOffHeapLE(srcPtr, prevW, dstPtr, dstW, dstH);
                } else {
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
                }
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

    private static void downsampleQuadOffHeapLE(long srcPtr, int srcW, long dstPtr, int dstW, int dstH) {
        final float[] qLut = SRGB_TO_LINEAR_QUARTER;
        final long srcRowBytes = (long) srcW << 2;
        final long srcRow2Bytes = srcRowBytes << 1;
        final long dstRowBytes = (long) dstW << 2;
        long sRow0 = srcPtr;
        long dRow = dstPtr;
        for (int y = 0; y < dstH; y++, sRow0 += srcRow2Bytes, dRow += dstRowBytes) {
            long s0 = sRow0;
            long s1 = sRow0 + srcRowBytes;
            long d = dRow;
            for (int x = 0; x < dstW; x++, s0 += 8L, s1 += 8L, d += 4L) {
                long p01 = MemoryUtil.memGetLong(s0);
                long p23 = MemoryUtil.memGetLong(s1);
                int c0 = (int) p01;
                int c1 = (int) (p01 >>> 32);
                int c2 = (int) p23;
                int c3 = (int) (p23 >>> 32);
                int outColor;
                if (c0 == c1 && c2 == c3 && c0 == c2) {
                    outColor = c0;
                } else if (((c0 & c1 & c2 & c3) >>> 24) == 255) {
                    float r = qLut[c0 & 0xFF] + qLut[c1 & 0xFF] + qLut[c2 & 0xFF] + qLut[c3 & 0xFF];
                    float g = qLut[(c0 >>> 8) & 0xFF] + qLut[(c1 >>> 8) & 0xFF]
                            + qLut[(c2 >>> 8) & 0xFF] + qLut[(c3 >>> 8) & 0xFF];
                    float b = qLut[(c0 >>> 16) & 0xFF] + qLut[(c1 >>> 16) & 0xFF]
                            + qLut[(c2 >>> 16) & 0xFF] + qLut[(c3 >>> 16) & 0xFF];
                    outColor = linearToSrgbFast(r, g, b, 255);
                } else {
                    outColor = weightedAverageColor(
                            weightedAverageColor(c0, c1),
                            weightedAverageColor(c2, c3));
                }
                MemoryUtil.memPutInt(d, outColor);
            }
        }
    }

    private static void downsampleQuad(int[] src, int srcW, int[] dst, int dstW, int dstH) {
        final float[] qLut = SRGB_TO_LINEAR_QUARTER;
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
                } else if (((c0 & c1 & c2 & c3) >>> 24) == 255) {
                    float r = qLut[c0 & 0xFF] + qLut[c1 & 0xFF] + qLut[c2 & 0xFF] + qLut[c3 & 0xFF];
                    float g = qLut[(c0 >>> 8) & 0xFF] + qLut[(c1 >>> 8) & 0xFF]
                            + qLut[(c2 >>> 8) & 0xFF] + qLut[(c3 >>> 8) & 0xFF];
                    float b = qLut[(c0 >>> 16) & 0xFF] + qLut[(c1 >>> 16) & 0xFF]
                            + qLut[(c2 >>> 16) & 0xFF] + qLut[(c3 >>> 16) & 0xFF];
                    dst[dstRow + x] = linearToSrgbFast(r, g, b, 255);
                } else {
                    dst[dstRow + x] = weightedAverageColor(
                            weightedAverageColor(c0, c1),
                            weightedAverageColor(c2, c3));
                }
            }
        }
    }

    public static int weightedAverageColor(int one, int two) {
        int a1 = (one >>> 24) & 0xFF;
        int a2 = (two >>> 24) & 0xFF;
        if (a1 == a2) {
            float r = (SRGB_TO_LINEAR[one & 0xFF] + SRGB_TO_LINEAR[two & 0xFF]) * 0.5F;
            float g = (SRGB_TO_LINEAR[(one >>> 8) & 0xFF] + SRGB_TO_LINEAR[(two >>> 8) & 0xFF]) * 0.5F;
            float b = (SRGB_TO_LINEAR[(one >>> 16) & 0xFF] + SRGB_TO_LINEAR[(two >>> 16) & 0xFF]) * 0.5F;
            return linearToSrgbFast(r, g, b, a1);
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
        float r = SRGB_TO_LINEAR[one & 0xFF] * w1 + SRGB_TO_LINEAR[two & 0xFF] * w2;
        float g = SRGB_TO_LINEAR[(one >>> 8) & 0xFF] * w1 + SRGB_TO_LINEAR[(two >>> 8) & 0xFF] * w2;
        float b = SRGB_TO_LINEAR[(one >>> 16) & 0xFF] * w1 + SRGB_TO_LINEAR[(two >>> 16) & 0xFF] * w2;
        return linearToSrgbFast(r, g, b, (a1 + a2) >> 1);
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
        int result;
        if (LITTLE_ENDIAN) {
            result = processTransparentOffHeapLE(ptr, totalPixels, fillInTransparent);
        } else {
            int[] px = borrowIntBuffer(TL_SRC, totalPixels);
            MemoryUtil.memIntBuffer(ptr, totalPixels).get(px, 0, totalPixels);
            result = processTransparentPixels(px, totalPixels, fillInTransparent);
            if ((result & 4) != 0) {
                MemoryUtil.memIntBuffer(ptr, totalPixels).put(px, 0, totalPixels);
            }
        }
        return switch (result & 3) {
            case 0 -> SpriteTransparencyLevel.OPAQUE;
            case 1 -> SpriteTransparencyLevel.TRANSPARENT;
            default -> SpriteTransparencyLevel.TRANSLUCENT;
        };
    }

    /** Zero-copy 64-bit off-heap transparency scan & optional zero-alpha fill for little-endian systems. */
    private static int processTransparentOffHeapLE(long ptr, int totalPixels, boolean fillInTransparent) {
        if (!fillInTransparent) {
            return scanTransparentOnlyLE(ptr, totalPixels);
        }
        if (isOpaqueFastLE(ptr, totalPixels)) {
            return 0;
        }
        return accumulateAndFillLE(ptr, totalPixels);
    }

    private static int scanTransparentOnlyLE(long ptr, int totalPixels) {
        boolean sawZeroAlpha = false;
        int i = 0;
        int quadLimit = totalPixels & ~3;
        long addr = ptr;
        for (; i < quadLimit; i += 4, addr += 16L) {
            long w0 = MemoryUtil.memGetLong(addr);
            long w1 = MemoryUtil.memGetLong(addr + 8L);
            if ((w0 & w1 & 0xFF000000FF000000L) != 0xFF000000FF000000L) {
                int a0 = (int) ((w0 >>> 24) & 0xFFL);
                int a1 = (int) (w0 >>> 56);
                int a2 = (int) ((w1 >>> 24) & 0xFFL);
                int a3 = (int) (w1 >>> 56);
                if ((a0 != 0 && a0 < 255) || (a1 != 0 && a1 < 255)
                        || (a2 != 0 && a2 < 255) || (a3 != 0 && a3 < 255)) {
                    return 2;
                }
                sawZeroAlpha = true;
            }
        }
        if (i + 2 <= totalPixels) {
            long w = MemoryUtil.memGetLong(addr);
            if ((w & 0xFF000000FF000000L) != 0xFF000000FF000000L) {
                int a0 = (int) ((w >>> 24) & 0xFFL);
                int a1 = (int) (w >>> 56);
                if ((a0 != 0 && a0 < 255) || (a1 != 0 && a1 < 255)) {
                    return 2;
                }
                sawZeroAlpha = true;
            }
            i += 2;
            addr += 8L;
        }
        if (i < totalPixels) {
            int a = MemoryUtil.memGetInt(addr) >>> 24;
            if (a == 0) {
                sawZeroAlpha = true;
            } else if (a < 255) {
                return 2;
            }
        }
        return sawZeroAlpha ? 1 : 0;
    }

    private static boolean isOpaqueFastLE(long ptr, int totalPixels) {
        if (totalPixels >= 4
                && ((MemoryUtil.memGetInt(ptr)
                        & MemoryUtil.memGetInt(ptr + ((long) (totalPixels - 1) << 2))
                        & MemoryUtil.memGetInt(ptr + ((long) (totalPixels >>> 1) << 2))
                        & 0xFF000000) != 0xFF000000)) {
            return false;
        }
        int i = 0;
        int quadLimit = totalPixels & ~3;
        long addr = ptr;
        for (; i < quadLimit; i += 4, addr += 16L) {
            if ((MemoryUtil.memGetLong(addr) & MemoryUtil.memGetLong(addr + 8L) & 0xFF000000FF000000L)
                    != 0xFF000000FF000000L) {
                return false;
            }
        }
        for (; i < totalPixels; i++, addr += 4L) {
            if ((MemoryUtil.memGetInt(addr) & 0xFF000000) != 0xFF000000) {
                return false;
            }
        }
        return true;
    }

    private static int accumulateAndFillLE(long ptr, int totalPixels) {
        boolean sawZeroAlpha = false;
        boolean sawTranslucent = false;

        final float[] lut = SRGB_TO_LINEAR;
        final float[] lut255 = SRGB_TO_LINEAR_255;
        float rSum = 0.0F;
        float gSum = 0.0F;
        float bSum = 0.0F;
        float totalWeight = 0.0F;

        int firstZeroIdx = -1;
        int i = 0;
        int quadLimit = totalPixels & ~3;
        long addr = ptr;
        for (; i < quadLimit; i += 4, addr += 16L) {
            long w0 = MemoryUtil.memGetLong(addr);
            long w1 = MemoryUtil.memGetLong(addr + 8L);
            if (((w0 | w1) & 0xFF000000FF000000L) == 0L) {
                if (firstZeroIdx < 0) {
                    firstZeroIdx = i;
                }
                continue;
            }
            if ((w0 & w1 & 0xFF000000FF000000L) == 0xFF000000FF000000L) {
                int c0 = (int) w0;
                int c1 = (int) (w0 >>> 32);
                int c2 = (int) w1;
                int c3 = (int) (w1 >>> 32);
                rSum += lut255[c0 & 0xFF] + lut255[c1 & 0xFF] + lut255[c2 & 0xFF] + lut255[c3 & 0xFF];
                gSum += lut255[(c0 >>> 8) & 0xFF] + lut255[(c1 >>> 8) & 0xFF]
                        + lut255[(c2 >>> 8) & 0xFF] + lut255[(c3 >>> 8) & 0xFF];
                bSum += lut255[(c0 >>> 16) & 0xFF] + lut255[(c1 >>> 16) & 0xFF]
                        + lut255[(c2 >>> 16) & 0xFF] + lut255[(c3 >>> 16) & 0xFF];
                totalWeight += 1020.0F;
                continue;
            }
            long cur = w0;
            for (int q = 0; q < 4; q++) {
                int color = (int) cur;
                cur = (q == 1) ? w1 : (cur >>> 32);
                int a = color >>> 24;
                if (a == 255) {
                    rSum += lut255[color & 0xFF];
                    gSum += lut255[(color >>> 8) & 0xFF];
                    bSum += lut255[(color >>> 16) & 0xFF];
                    totalWeight += 255.0F;
                } else if (a == 0) {
                    if (firstZeroIdx < 0) {
                        firstZeroIdx = i + q;
                    }
                } else {
                    sawTranslucent = true;
                    float w = a;
                    rSum += lut[color & 0xFF] * w;
                    gSum += lut[(color >>> 8) & 0xFF] * w;
                    bSum += lut[(color >>> 16) & 0xFF] * w;
                    totalWeight += w;
                }
            }
        }
        for (; i < totalPixels; i++, addr += 4L) {
            int color = MemoryUtil.memGetInt(addr);
            int a = color >>> 24;
            if (a == 255) {
                rSum += lut255[color & 0xFF];
                gSum += lut255[(color >>> 8) & 0xFF];
                bSum += lut255[(color >>> 16) & 0xFF];
                totalWeight += 255.0F;
            } else if (a == 0) {
                if (firstZeroIdx < 0) {
                    firstZeroIdx = i;
                }
            } else {
                sawTranslucent = true;
                float w = a;
                rSum += lut[color & 0xFF] * w;
                gSum += lut[(color >>> 8) & 0xFF] * w;
                bSum += lut[(color >>> 16) & 0xFF] * w;
                totalWeight += w;
            }
        }
        int level = sawTranslucent ? 2 : (firstZeroIdx >= 0 ? 1 : 0);
        if (firstZeroIdx < 0 || totalWeight <= 0.0F) {
            return level;
        }
        int fillColor = linearToSrgbFast(
                rSum / totalWeight,
                gSum / totalWeight,
                bSum / totalWeight,
                0);
        fillZeroAlphaLE(ptr, firstZeroIdx & ~1, totalPixels, fillColor);
        return level | 4;
    }

    private static void fillZeroAlphaLE(long ptr, int startPixel, int totalPixels, int fillColor) {
        long fillLong = ((long) fillColor << 32) | (fillColor & 0xFFFFFFFFL);
        int twoLimit = totalPixels & ~1;
        int wIdx = startPixel;
        long writeAddr = ptr + ((long) startPixel << 2);
        for (; wIdx < twoLimit; wIdx += 2, writeAddr += 8L) {
            long w = MemoryUtil.memGetLong(writeAddr);
            long mask = w & 0xFF000000FF000000L;
            if (mask == 0L) {
                MemoryUtil.memPutLong(writeAddr, fillLong);
            } else if ((mask & 0xFF000000L) == 0L) {
                MemoryUtil.memPutInt(writeAddr, fillColor);
            } else if ((mask >>> 56) == 0L) {
                MemoryUtil.memPutInt(writeAddr + 4L, fillColor);
            }
        }
        if (wIdx < totalPixels && (MemoryUtil.memGetInt(writeAddr) & 0xFF000000) == 0) {
            MemoryUtil.memPutInt(writeAddr, fillColor);
        }
    }

    /** Returns transparency ordinal, with bit 2 set when transparent pixels were filled. */
    public static int processTransparentPixels(int[] px, int totalPixels, boolean fillInTransparent) {
        if (!fillInTransparent) {
            boolean sawZeroAlpha = false;
            int i = 0;
            int unrollLimit = totalPixels & ~3;
            while (i < unrollLimit) {
                int p0 = px[i];
                int p1 = px[i + 1];
                int p2 = px[i + 2];
                int p3 = px[i + 3];
                if ((p0 & p1 & p2 & p3 & 0xFF000000) != 0xFF000000) {
                    int a0 = p0 >>> 24;
                    int a1 = p1 >>> 24;
                    int a2 = p2 >>> 24;
                    int a3 = p3 >>> 24;
                    if ((a0 != 0 && a0 < 255) || (a1 != 0 && a1 < 255)
                            || (a2 != 0 && a2 < 255) || (a3 != 0 && a3 < 255)) {
                        return 2;
                    }
                    sawZeroAlpha = true;
                }
                i += 4;
            }
            while (i < totalPixels) {
                int a = px[i++] >>> 24;
                if (a == 0) {
                    sawZeroAlpha = true;
                } else if (a < 255) {
                    return 2;
                }
            }
            return sawZeroAlpha ? 1 : 0;
        }

        int i;
        if (totalPixels >= 16
                && (px[0] & px[totalPixels - 1] & px[totalPixels >>> 1] & 0xFF000000) == 0xFF000000) {
            i = 0;
            while (i + 4 <= totalPixels && (px[i] & px[i + 1] & px[i + 2] & px[i + 3] & 0xFF000000) == 0xFF000000) {
                i += 4;
            }
            while (i < totalPixels && (px[i] & 0xFF000000) == 0xFF000000) {
                i++;
            }
            if (i == totalPixels) {
                return 0;
            }
        }

        boolean sawZeroAlpha = false;
        boolean sawTranslucent = false;

        final float[] lut = SRGB_TO_LINEAR;
        final float[] lut255 = SRGB_TO_LINEAR_255;
        float rSum = 0.0F;
        float gSum = 0.0F;
        float bSum = 0.0F;
        float totalWeight = 0.0F;

        for (i = 0; i < totalPixels; ) {
            if (i + 4 <= totalPixels) {
                int c0 = px[i];
                int c1 = px[i + 1];
                int c2 = px[i + 2];
                int c3 = px[i + 3];
                if (((c0 | c1 | c2 | c3) & 0xFF000000) == 0) {
                    sawZeroAlpha = true;
                    i += 4;
                    continue;
                }
                if ((c0 & c1 & c2 & c3 & 0xFF000000) == 0xFF000000) {
                    rSum += lut255[c0 & 0xFF] + lut255[c1 & 0xFF] + lut255[c2 & 0xFF] + lut255[c3 & 0xFF];
                    gSum += lut255[(c0 >>> 8) & 0xFF] + lut255[(c1 >>> 8) & 0xFF]
                            + lut255[(c2 >>> 8) & 0xFF] + lut255[(c3 >>> 8) & 0xFF];
                    bSum += lut255[(c0 >>> 16) & 0xFF] + lut255[(c1 >>> 16) & 0xFF]
                            + lut255[(c2 >>> 16) & 0xFF] + lut255[(c3 >>> 16) & 0xFF];
                    totalWeight += 1020.0F;
                    i += 4;
                    continue;
                }
            }
            int color = px[i++];
            int a = color >>> 24;
            if (a == 255) {
                rSum += lut255[color & 0xFF];
                gSum += lut255[(color >>> 8) & 0xFF];
                bSum += lut255[(color >>> 16) & 0xFF];
                totalWeight += 255.0F;
            } else if (a == 0) {
                sawZeroAlpha = true;
            } else {
                sawTranslucent = true;
                float w = a;
                rSum += lut[color & 0xFF] * w;
                gSum += lut[(color >>> 8) & 0xFF] * w;
                bSum += lut[(color >>> 16) & 0xFF] * w;
                totalWeight += w;
            }
        }
        int level = sawTranslucent ? 2 : (sawZeroAlpha ? 1 : 0);
        if (!sawZeroAlpha) {
            return level;
        }

        if (totalWeight > 0.0F) {
            int fillColor = linearToSrgbFast(
                    rSum / totalWeight,
                    gSum / totalWeight,
                    bSum / totalWeight,
                    0);
            for (i = 0; i < totalPixels; i++) {
                if ((px[i] & 0xFF000000) == 0) {
                    px[i] = fillColor;
                }
            }
            return level | 4;
        }
        return level;
    }

    private static final float LINEAR_MIN_BOUND = Float.intBitsToFloat(956301312);
    private static final float LINEAR_MAX_BOUND = Float.intBitsToFloat(1065353215);
    private static final int[] TO_SRGB8_TABLE = {
            7536653, 7995405, 8388621, 8847373, 9240589, 9699341, 10092557, 10551309,
            10944538, 11796506, 12648474, 13500442, 14286874, 15138842, 15990810, 16842778,
            17694771, 19398707, 21037107, 22741043, 24444979, 26148915, 27787315, 29491251,
            31195239, 34537575, 37945447, 41287783, 44695655, 48037991, 51445863, 54788199,
            58196174, 64946382, 71696590, 78446798, 85197006, 91947205, 98369724, 104530101,
            110559576, 121766210, 132317488, 142278944, 151716114, 160694534, 169279740, 177537266,
            185532875, 200540590, 214630805, 227869056, 240517486, 252510558, 263979344, 274923843,
            285672036, 305660478, 324469277, 342229505, 359006697, 374997459, 390332864, 405012911,
            419300145, 446038782, 471139026, 494797485, 517210765, 538575472, 559022678, 578617920,
            597623875, 633340926, 666829764, 698418066, 728367975, 756876097, 784204575, 810353408,
            835782064, 883426645, 928122119, 970261701, 1010238603, 1048314968, 1084752938, 1119683585,
            1153566616, 1217267486, 1276905142, 1333134941, 1386546704, 1437337036, 1485964687, 1532560729,
            1577847331, 1662781824, 1742407926, 1817512063, 1888749592, 1956644797, 2021459820, 2083718947
    };

    private static int linearToSrgb8(float c, int[] table) {
        if (!(c > LINEAR_MIN_BOUND)) {
            c = LINEAR_MIN_BOUND;
        } else if (c > LINEAR_MAX_BOUND) {
            c = LINEAR_MAX_BOUND;
        }
        int bits = Float.floatToRawIntBits(c);
        int entry = table[(bits - 956301312) >> 20];
        return (((entry >>> 16) << 9) + ((entry & 0xFFFF) * ((bits >>> 12) & 0xFF))) >>> 16;
    }

    private static int linearToSrgbFast(float r, float g, float b, int a) {
        final int[] table = TO_SRGB8_TABLE;
        return ((a & 0xFF) << 24)
                | (linearToSrgb8(b, table) << 16)
                | (linearToSrgb8(g, table) << 8)
                | linearToSrgb8(r, table);
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
        int workers = Math.min(Math.max(2, Runtime.getRuntime().availableProcessors()), 16);
        AtomicInteger next = new AtomicInteger(0);
        final int chunk = 4;
        Runnable workLoop = () -> {
            int start;
            while ((start = next.getAndAdd(chunk)) < arr.length) {
                int end = Math.min(arr.length, start + chunk);
                for (int i = start; i < end; i++) {
                    arr[i].m_245424_().m_246368_(mipLevel);
                }
            }
        };
        List<ForkJoinTask<?>> tasks = new ArrayList<>(workers - 1);
        for (int w = 1; w < workers; w++) {
            tasks.add(ForkJoinPool.commonPool().submit(workLoop));
        }
        workLoop.run();
        for (ForkJoinTask<?> task : tasks) {
            task.join();
        }
    }
}
