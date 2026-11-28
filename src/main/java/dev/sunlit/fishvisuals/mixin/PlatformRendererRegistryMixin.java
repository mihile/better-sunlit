package dev.sunlit.fishvisuals.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Pseudo
@Mixin(targets = {
        "com.blackgear.platform.client.v2.render.ItemRendererRegistry",
        "com.blackgear.platform.client.v2.render.DynamicItemRenderer",
        "com.blackgear.platform.client.v2.render.BlockRendererRegistry",
        "com.blackgear.platform.client.v2.render.forge.BuiltinItemRendererRegistryImpl"
}, remap = false)
public abstract class PlatformRendererRegistryMixin {
    @Shadow(remap = false) @Final @Mutable
    private static Map<Object, Object> RENDERERS;

    @Inject(method = "<clinit>", at = @At("TAIL"), remap = false)
    private static void sunlit$makeRendererRegistryConcurrent(CallbackInfo ci) {
        // ModelBakery can iterate these registries while renderers are being registered.
        // Publish a concurrent map from class initialization so readers and writers share it.
        RENDERERS = new ConcurrentHashMap<>(RENDERERS);
    }
}
