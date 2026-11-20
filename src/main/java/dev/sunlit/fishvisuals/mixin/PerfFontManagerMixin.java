package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.providers.UnihexProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Replaces {@code FontManager.finalizeProviderLoading} ({@code m_284489_}) to skip merging and
 * no-op scanning 55,000+ pure Unihex codepoints across every font family while preserving small
 * provider warming without allocating {@code Lists.reverse(providers)}.
 */
@Mixin(value = FontManager.class, remap = false)
public abstract class PerfFontManagerMixin {
    @Inject(method = "m_284489_", at = @At("HEAD"), cancellable = true)
    private void betterSunlit$fastFinalizeProviderLoading(
            List<GlyphProvider> providers, GlyphProvider fallback, CallbackInfo ci) {
        ci.cancel();
        providers.add(0, fallback);
        int count = providers.size();
        IntOpenHashSet smallSet = null;
        for (int i = 0; i < count; i++) {
            GlyphProvider provider = providers.get(i);
            if (provider instanceof UnihexProvider
                    || "org.embeddedt.modernfix.render.font.LazyGlyphProvider".equals(provider.getClass().getName())) {
                continue;
            }
            IntSet supported = provider.m_6990_();
            if (supported == null || supported.isEmpty() || supported.size() > 4096) {
                continue;
            }
            if (smallSet == null) {
                smallSet = new IntOpenHashSet(Math.max(256, supported.size() * 2), 0.75F);
            }
            smallSet.addAll(supported);
        }
        if (smallSet == null || smallSet.isEmpty()) {
            return;
        }
        IntIterator it = smallSet.iterator();
        while (it.hasNext()) {
            int cp = it.nextInt();
            if (cp == 32) {
                continue;
            }
            for (int i = count - 1; i >= 0; i--) {
                if (providers.get(i).m_214022_(cp) != null) {
                    break;
                }
            }
        }
    }
}
