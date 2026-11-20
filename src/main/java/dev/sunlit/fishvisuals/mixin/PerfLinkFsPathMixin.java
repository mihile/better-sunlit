package dev.sunlit.fishvisuals.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Replaces recursive {@code ImmutableList.Builder.addAll} chains in {@code LinkFSPath#m_246070_}
 * with a single iterative ancestor walk into a fixed array.
 */
@Mixin(targets = "net.minecraft.server.packs.linkfs.LinkFSPath", remap = false)
public abstract class PerfLinkFsPathMixin {
    @Unique
    private static final MethodHandle SUNLIT$NAME_GETTER = sunlit$resolveFieldGetter("f_244036_", String.class);
    @Unique
    private static final MethodHandle SUNLIT$PARENT_GETTER = sunlit$resolveParentGetter();

    @Shadow
    @Final
    private String f_244036_;

    @Shadow
    private List<String> f_244015_;

    @Unique
    private static MethodHandle sunlit$resolveFieldGetter(String name, Class<?> retType) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> self = lookup.lookupClass();
            Field f = self.getDeclaredField(name);
            return lookup.findGetter(self, name, f.getType()).asType(MethodType.methodType(retType, Object.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private static MethodHandle sunlit$resolveParentGetter() {
        return sunlit$resolveFieldGetter("f_244587_", Object.class);
    }

    @Inject(method = "m_246070_()Ljava/util/List;", at = @At("HEAD"), cancellable = true)
    private void sunlit$iterativePathToRoot(CallbackInfoReturnable<List<String>> cir) {
        if (this.f_244036_.isEmpty()) {
            cir.setReturnValue(List.of());
            return;
        }
        List<String> cached = this.f_244015_;
        if (cached == null) {
            if (SUNLIT$NAME_GETTER == null || SUNLIT$PARENT_GETTER == null) {
                return;
            }
            try {
                int depth = 0;
                for (Object cur = this; cur != null; ) {
                    String n = (String) SUNLIT$NAME_GETTER.invokeExact(cur);
                    if (n.isEmpty()) {
                        break;
                    }
                    depth++;
                    cur = (Object) SUNLIT$PARENT_GETTER.invokeExact(cur);
                }
                String[] parts = new String[depth];
                Object cur = this;
                for (int i = depth - 1; i >= 0; i--) {
                    parts[i] = (String) SUNLIT$NAME_GETTER.invokeExact(cur);
                    cur = (Object) SUNLIT$PARENT_GETTER.invokeExact(cur);
                }
                cached = List.of(parts);
                this.f_244015_ = cached;
            } catch (Throwable ignored) {
                return;
            }
        }
        cir.setReturnValue(cached);
    }
}
