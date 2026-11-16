package dev.sunlit.fishvisuals.mixin;

import net.mehvahdjukaar.mysticaloaktree.client.TreeLoreManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Defers Mystical Oak Tree's remote HTTP dialogue/location downloads from client startup to
 * {@code getRandomDialogue}, which already invokes {@code initIfNeeded()} lazily on first use.
 */
@Mixin(value = TreeLoreManager.class, remap = false)
public abstract class PerfMysticalTreeLoreMixin {
    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private static void sunlit$deferStartupHttpDownloads(CallbackInfo ci) {
        ci.cancel();
    }
}
