package dev.sunlit.fishvisuals.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Map;

/**
 * Wraps {@code ForgeEventsRemapper}'s unsynchronized {@code Object2ObjectOpenHashMap} lookup maps
 * so concurrent wrapper registration and listener remapping during parallel mod loading never race.
 */
@Pseudo
@Mixin(targets = "org.violetmoon.zetaimplforge.event.ForgeEventsRemapper", priority = 2000, remap = false)
public abstract class PerfForgeEventsRemapperMixin {

    @Shadow(remap = false)
    @Final
    @Mutable
    private Map<Object, Object> forgeToZetaMap;

    @Shadow(remap = false)
    @Final
    @Mutable
    private Map<Object, Object> zetaToForgeMap;

    @Shadow(remap = false)
    @Final
    @Mutable
    private Map<Object, Object> generics;

    @Shadow(remap = false)
    @Final
    @Mutable
    private Map<Object, Object> zetaToForgeEventClassHack;

    @Inject(
            method = "<init>(Ljava/lang/Class;Ljava/lang/Class;)V",
            at = @At("RETURN"),
            remap = false,
            require = 1
    )
    private void sunlit$initConcurrentRemapperMaps(
            Class<?> zetaEventRoot,
            Class<?> forgeEventRoot,
            CallbackInfo ci) {
        if (this.forgeToZetaMap != null) {
            this.forgeToZetaMap = Collections.synchronizedMap(this.forgeToZetaMap);
        }
        if (this.zetaToForgeMap != null) {
            this.zetaToForgeMap = Collections.synchronizedMap(this.zetaToForgeMap);
        }
        if (this.generics != null) {
            this.generics = Collections.synchronizedMap(this.generics);
        }
        if (this.zetaToForgeEventClassHack != null) {
            this.zetaToForgeEventClassHack = Collections.synchronizedMap(this.zetaToForgeEventClassHack);
        }
    }
}
