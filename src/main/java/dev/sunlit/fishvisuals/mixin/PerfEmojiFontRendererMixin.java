package dev.sunlit.fishvisuals.mixin;

import com.google.common.cache.LoadingCache;
import com.hrznstudio.emojiful.api.Emoji;
import dev.sunlit.bettersunlit.client.EmojiRenderPerf;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.apache.commons.lang3.tuple.Pair;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.function.Function;

/**
 * Caps {@code EmojiFontRenderer.RECENT_STRINGS} to 4096 entries and adds a zero-allocation
 * vanilla {@link Font} fast path (preserving {@code chat_heads}) when no emoji trigger exists.
 * Paired with {@code EntryPostApplyPatches.patchEmojiFontRenderer}.
 */
@Pseudo
@Mixin(targets = "com.hrznstudio.emojiful.render.EmojiFontRenderer", priority = 2000, remap = false)
public abstract class PerfEmojiFontRendererMixin extends Font {

    @Shadow
    public static LoadingCache<String, Pair<String, HashMap<Integer, Emoji>>> RECENT_STRINGS;

    protected PerfEmojiFontRendererMixin(Function<ResourceLocation, FontSet> fonts, boolean filterFishyGlyphs) {
        super(fonts, filterFishyGlyphs);
    }

    @Inject(method = "<clinit>()V", at = @At("TAIL"))
    private static void sunlit$capRecentStringsCache(CallbackInfo ci) {
        RECENT_STRINGS = EmojiRenderPerf.createBoundedCache();
    }

    @Unique
    private float sunlit$superRenderText(
            String text,
            float x,
            float y,
            int color,
            boolean dropShadow,
            Matrix4f pose,
            MultiBufferSource buffer,
            Font.DisplayMode displayMode,
            int bgColor,
            int packedLight) {
        throw new AssertionError("Patched in postApply");
    }

    @Unique
    public int sunlit$origWidthString(String text) {
        throw new AssertionError("Patched in postApply");
    }

    @Unique
    public int sunlit$origWidthSeq(FormattedCharSequence seq) {
        throw new AssertionError("Patched in postApply");
    }

    @Unique
    public float sunlit$origDrawString(
            String text,
            float x,
            float y,
            int color,
            boolean dropShadow,
            Matrix4f pose,
            MultiBufferSource buffer,
            Font.DisplayMode displayMode,
            int bgColor,
            int packedLight) {
        throw new AssertionError("Patched in postApply");
    }

    @Unique
    public int sunlit$origDrawSeq(
            FormattedCharSequence seq,
            float x,
            float y,
            int color,
            boolean dropShadow,
            Matrix4f pose,
            MultiBufferSource buffer,
            Font.DisplayMode displayMode,
            int bgColor,
            int packedLight) {
        throw new AssertionError("Patched in postApply");
    }

    @Unique
    public int sunlit$fastWidthString(String text) {
        if (text == null || text.isEmpty() || !EmojiRenderPerf.isRenderEmojiEnabled()
                || !EmojiRenderPerf.mightContainEmoji(text)) {
            return super.m_92895_(text);
        }
        return this.sunlit$origWidthString(text);
    }

    @Unique
    public int sunlit$fastWidthSeq(FormattedCharSequence seq) {
        if (seq == null || !EmojiRenderPerf.isRenderEmojiEnabled()) {
            return super.m_92724_(seq);
        }
        String candidate = EmojiRenderPerf.extractIfMightContainEmoji(seq);
        if (candidate == null) {
            return super.m_92724_(seq);
        }
        return this.m_92895_(candidate);
    }

    @Unique
    public float sunlit$fastDrawString(
            String text,
            float x,
            float y,
            int color,
            boolean dropShadow,
            Matrix4f pose,
            MultiBufferSource buffer,
            Font.DisplayMode displayMode,
            int bgColor,
            int packedLight) {
        if (text == null || text.isEmpty()) {
            return 0.0F;
        }
        if (!EmojiRenderPerf.isRenderEmojiEnabled() || !EmojiRenderPerf.mightContainEmoji(text)) {
            return this.sunlit$superRenderText(text, x, y, color, dropShadow, pose, buffer, displayMode, bgColor, packedLight);
        }
        Pair<String, HashMap<Integer, Emoji>> pair = EmojiRenderPerf.getCachedOrLoad(RECENT_STRINGS, text);
        if (pair != null && pair.getRight().isEmpty() && text.equals(pair.getLeft())) {
            return this.sunlit$superRenderText(text, x, y, color, dropShadow, pose, buffer, displayMode, bgColor, packedLight);
        }
        return this.sunlit$origDrawString(text, x, y, color, dropShadow, pose, buffer, displayMode, bgColor, packedLight);
    }

    @Unique
    public int sunlit$fastDrawSeq(
            FormattedCharSequence seq,
            float x,
            float y,
            int color,
            boolean dropShadow,
            Matrix4f pose,
            MultiBufferSource buffer,
            Font.DisplayMode displayMode,
            int bgColor,
            int packedLight) {
        if (seq == null || !EmojiRenderPerf.isRenderEmojiEnabled()) {
            return super.m_272191_(seq, x, y, color, dropShadow, pose, buffer, displayMode, bgColor, packedLight);
        }
        String candidate = EmojiRenderPerf.extractIfMightContainEmoji(seq);
        if (candidate == null || candidate.isEmpty()) {
            return super.m_272191_(seq, x, y, color, dropShadow, pose, buffer, displayMode, bgColor, packedLight);
        }
        Pair<String, HashMap<Integer, Emoji>> pair = EmojiRenderPerf.getCachedOrLoad(RECENT_STRINGS, candidate);
        if (pair != null && pair.getRight().isEmpty() && candidate.equals(pair.getLeft())) {
            return super.m_272191_(seq, x, y, color, dropShadow, pose, buffer, displayMode, bgColor, packedLight);
        }
        return this.sunlit$origDrawSeq(seq, x, y, color, dropShadow, pose, buffer, displayMode, bgColor, packedLight);
    }
}
