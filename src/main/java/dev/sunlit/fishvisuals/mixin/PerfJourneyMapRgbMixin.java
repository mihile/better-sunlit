package dev.sunlit.fishvisuals.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eliminates regex {@code Pattern.compile} and temporary string allocations from
 * {@code journeymap.client.cartography.color.RGB.hexToInt} during biome map painting.
 */
@Pseudo
@Mixin(targets = "journeymap.client.cartography.color.RGB", priority = 2000, remap = false)
public abstract class PerfJourneyMapRgbMixin {
    @Inject(method = "hexToInt(Ljava/lang/String;)I", at = @At("HEAD"), cancellable = true)
    private static void sunlit$fastHexToInt(String hex, CallbackInfoReturnable<Integer> cir) {
        if (hex == null || hex.isEmpty()) {
            cir.setReturnValue(0);
            return;
        }
        int len = hex.length();
        boolean skippedHash = false;
        int digits = 0;
        int val = 0;
        int firstDigit = 0;
        for (int i = 0; i < len; i++) {
            char c = hex.charAt(i);
            if (c == '#' && !skippedHash) {
                skippedHash = true;
                continue;
            }
            int d;
            if (c >= '0' && c <= '9') {
                d = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                d = 10 + (c - 'a');
            } else if (c >= 'A' && c <= 'F') {
                d = 10 + (c - 'A');
            } else {
                return;
            }
            if (digits == 0) {
                firstDigit = d;
            }
            digits++;
            if (digits > 8 || (digits == 8 && firstDigit > 7)) {
                return;
            }
            val = (val << 4) | d;
        }
        if (digits > 0) {
            cir.setReturnValue(0xFF000000 | val);
        }
    }
}
