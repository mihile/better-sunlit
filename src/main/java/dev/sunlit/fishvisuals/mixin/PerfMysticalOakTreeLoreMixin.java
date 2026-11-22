package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.MysticalOakTreePerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Translates Mystical Oak Tree dialogue lines before placeholder substitution when Korean is active.
 */
@Pseudo
@Mixin(targets = "net.mehvahdjukaar.mysticaloaktree.client.TreeLoreManager", priority = 2000, remap = false)
public abstract class PerfMysticalOakTreeLoreMixin {
    @ModifyVariable(method = "formatText", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private static String sunlit$translateWisdomLine(String text) {
        return MysticalOakTreePerf.translateIfKorean(text);
    }
}
