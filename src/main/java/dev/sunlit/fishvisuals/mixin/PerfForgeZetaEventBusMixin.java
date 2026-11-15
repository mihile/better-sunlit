package dev.sunlit.fishvisuals.mixin;

import net.minecraftforge.eventbus.api.IEventBus;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.violetmoon.zeta.Zeta;
import org.violetmoon.zetaimplforge.event.ForgeEventsRemapper;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.Map;

/**
 * Wraps {@code ForgeZetaEventBus.convertedHandlers} with a synchronized map at construction time
 * so parallel FML mod construction threads (e.g. Quark and Supplementaries subscribing to
 * {@code Quark.ZETA.loadBus} simultaneously) cannot corrupt {@code Object2ObjectOpenHashMap.rehash}.
 */
@Pseudo
@Mixin(targets = "org.violetmoon.zetaimplforge.event.ForgeZetaEventBus", priority = 2000, remap = false)
public abstract class PerfForgeZetaEventBusMixin {

    @Shadow(remap = false)
    @Final
    @Mutable
    private Map<Object, Object> convertedHandlers;

    @Inject(
            method = "<init>(Ljava/lang/Class;Ljava/lang/Class;Lorg/apache/logging/log4j/Logger;Lnet/minecraftforge/eventbus/api/IEventBus;Ljava/lang/Class;Lorg/violetmoon/zeta/Zeta;Lorg/violetmoon/zetaimplforge/event/ForgeEventsRemapper;)V",
            at = @At("RETURN"),
            remap = false,
            require = 1
    )
    private void sunlit$initConcurrentHandlersMap(
            Class<? extends Annotation> subscriberAnnotation,
            Class<?> eventRoot,
            Logger logSpam,
            IEventBus forgeBus,
            Class<?> forgeEventRoot,
            Zeta ofZeta,
            ForgeEventsRemapper<?, ?> remapper,
            CallbackInfo ci) {
        if (this.convertedHandlers != null) {
            this.convertedHandlers = Collections.synchronizedMap(this.convertedHandlers);
        }
    }
}
