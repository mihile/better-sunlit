package dev.sunlit.fishvisuals.mixin;

import net.minecraft.FileUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces {@code FileUtil.isValidStrictPathSegment} ({@code m_245636_}) regex with an ASCII loop. */
@Mixin(value = FileUtil.class, remap = false)
public abstract class PerfFileUtilMixin {
    @Inject(method = "m_245636_", at = @At("HEAD"), cancellable = true)
    private static void betterSunlit$fastValidPathSegment(
            String segment, CallbackInfoReturnable<Boolean> cir) {
        if (segment == null || segment.isEmpty()) {
            cir.setReturnValue(Boolean.FALSE);
            return;
        }
        int len = segment.length();
        for (int i = 0; i < len; i++) {
            char c = segment.charAt(i);
            if (!((c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '.'
                    || c == '_')) {
                cir.setReturnValue(Boolean.FALSE);
                return;
            }
        }
        cir.setReturnValue(Boolean.TRUE);
    }
}
