package dev.sunlit.bettersunlit.client;

import icyllis.modernui.graphics.text.FontFamily;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ModernUiPackFonts {
    private ModernUiPackFonts() {}

    public interface BitmapOverride {
        boolean sunlit$isPackOverride();
    }

    public static boolean hasPackOverride(ResourceManager resources, ResourceLocation bitmap) {
        if (!bitmap.m_135827_().equals("minecraft") || !bitmap.m_135815_().startsWith("font/")) {
            return false;
        }
        List<Resource> stack = resources.m_213829_(bitmap.m_246208_("textures/"));
        if (stack.size() < 2) return false;
        Resource active = stack.get(stack.size() - 1);
        if (active.m_215506_().equals("vanilla")) return false;
        for (Resource base : stack) {
            if (!base.m_215506_().equals("vanilla")) continue;
            try (var vanilla = base.m_215507_(); var replacement = active.m_215507_()) {
                return !Arrays.equals(vanilla.readAllBytes(), replacement.readAllBytes());
            } catch (IOException ignored) {
                return false;
            }
        }
        return false;
    }

    public static void keepPackOverrides(Set<FontFamily> selected, List<FontFamily> original) {
        var ordered = new LinkedHashSet<FontFamily>();
        for (FontFamily family : original) {
            if (selected.contains(family)
                    || family.getClosestMatch(0) instanceof BitmapOverride bitmap && bitmap.sunlit$isPackOverride()) {
                ordered.add(family);
            }
        }
        if (ordered.size() == selected.size()) return;
        selected.clear();
        selected.addAll(ordered);
    }
}
