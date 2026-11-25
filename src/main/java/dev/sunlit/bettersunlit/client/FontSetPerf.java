package dev.sunlit.bettersunlit.client;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.gui.font.glyphs.SpecialGlyphs;
import net.minecraft.client.gui.font.providers.UnihexProvider;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Skips eager {@code UnihexProvider} / ModernFix {@code LazyGlyphProvider} glyph-set deserialization
 * during {@code FontSet.reload}, keeping those providers attached for lazy on-demand lookup while
 * populating {@code glyphsByWidth} from all non-lazy providers without extra allocations.
 */
public final class FontSetPerf {
    private static final String LAZY_GLYPH_PROVIDER_CLASS =
            "org.embeddedt.modernfix.render.font.LazyGlyphProvider";

    private FontSetPerf() {
    }

    public static boolean isLazyOrUnihex(GlyphProvider provider) {
        if (provider == null) {
            return false;
        }
        return provider instanceof UnihexProvider
                || LAZY_GLYPH_PROVIDER_CLASS.equals(provider.getClass().getName());
    }

    public static void reloadProviders(
            List<GlyphProvider> providers,
            Int2ObjectMap<IntList> glyphsByWidth,
            List<GlyphProvider> destProviders) {
        if (providers == null || providers.isEmpty()) {
            return;
        }
        IntOpenHashSet allCodepoints = new IntOpenHashSet();
        List<GlyphProvider> eagerProviders = new ArrayList<>(providers.size());
        Set<GlyphProvider> usedProviders = Sets.newHashSetWithExpectedSize(providers.size());

        for (int i = 0, n = providers.size(); i < n; i++) {
            GlyphProvider provider = providers.get(i);
            if (isLazyOrUnihex(provider)) {
                usedProviders.add(provider);
                continue;
            }
            IntSet supported = provider.m_6990_();
            if (supported != null && !supported.isEmpty()) {
                allCodepoints.addAll(supported);
            }
            eagerProviders.add(provider);
        }

        if (!allCodepoints.isEmpty() && !eagerProviders.isEmpty()) {
            int eagerCount = eagerProviders.size();
            IntIterator it = allCodepoints.iterator();
            while (it.hasNext()) {
                int cp = it.nextInt();
                for (int j = 0; j < eagerCount; j++) {
                    GlyphProvider provider = eagerProviders.get(j);
                    GlyphInfo info = provider.m_214022_(cp);
                    if (info != null) {
                        usedProviders.add(provider);
                        if (info != SpecialGlyphs.MISSING) {
                            int ceilAdv = Mth.m_14167_(info.m_83827_(false));
                            IntList bucket = glyphsByWidth.get(ceilAdv);
                            if (bucket == null) {
                                bucket = new IntArrayList();
                                glyphsByWidth.put(ceilAdv, bucket);
                            }
                            bucket.add(cp);
                        }
                        break;
                    }
                }
            }
        }

        for (int i = 0, n = providers.size(); i < n; i++) {
            GlyphProvider provider = providers.get(i);
            if (usedProviders.contains(provider)) {
                destProviders.add(provider);
            }
        }
    }
}
