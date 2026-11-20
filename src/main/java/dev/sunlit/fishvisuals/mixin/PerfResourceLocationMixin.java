package dev.sunlit.fishvisuals.mixin;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fast ASCII lookup-table path and namespace validation for {@link ResourceLocation},
 * avoiding per-character validation calls and repeated Mixin callback allocations on normal
 * identifiers while delegating non-matching characters to {@code m_135828_} / {@code m_135835_}
 * so downstream Mixin relaxations remain intact.
 */
@Mixin(value = ResourceLocation.class, priority = 2000, remap = false)
public abstract class PerfResourceLocationMixin {
    @Unique
    private static final boolean[] SUNLIT$VALID_PATH = new boolean[128];
    @Unique
    private static final boolean[] SUNLIT$VALID_NS = new boolean[128];

    static {
        for (char c = 'a'; c <= 'z'; c++) {
            SUNLIT$VALID_PATH[c] = true;
            SUNLIT$VALID_NS[c] = true;
        }
        for (char c = '0'; c <= '9'; c++) {
            SUNLIT$VALID_PATH[c] = true;
            SUNLIT$VALID_NS[c] = true;
        }
        SUNLIT$VALID_PATH['_'] = true;
        SUNLIT$VALID_PATH['-'] = true;
        SUNLIT$VALID_PATH['.'] = true;
        SUNLIT$VALID_PATH['/'] = true;
        SUNLIT$VALID_NS['_'] = true;
        SUNLIT$VALID_NS['-'] = true;
        SUNLIT$VALID_NS['.'] = true;
    }

    @Shadow
    public static boolean m_135828_(char ch) {
        throw new AssertionError();
    }

    @Shadow
    private static boolean m_135835_(char ch) {
        throw new AssertionError();
    }

    @Inject(method = "m_135841_(Ljava/lang/String;)Z", at = @At("HEAD"), cancellable = true)
    private static void sunlit$fastValidPath(String path, CallbackInfoReturnable<Boolean> cir) {
        final boolean[] lut = SUNLIT$VALID_PATH;
        final int len = path.length();
        for (int i = 0; i < len; i++) {
            char c = path.charAt(i);
            if (c >= 128 || !lut[c]) {
                if (!m_135828_(c)) {
                    cir.setReturnValue(Boolean.FALSE);
                    return;
                }
            }
        }
        cir.setReturnValue(Boolean.TRUE);
    }

    @Inject(method = "m_135843_(Ljava/lang/String;)Z", at = @At("HEAD"), cancellable = true)
    private static void sunlit$fastValidNamespace(String namespace, CallbackInfoReturnable<Boolean> cir) {
        final boolean[] lut = SUNLIT$VALID_NS;
        final int len = namespace.length();
        for (int i = 0; i < len; i++) {
            char c = namespace.charAt(i);
            if (c >= 128 || !lut[c]) {
                if (!m_135835_(c)) {
                    cir.setReturnValue(Boolean.FALSE);
                    return;
                }
            }
        }
        cir.setReturnValue(Boolean.TRUE);
    }
}
