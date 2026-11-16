package dev.sunlit.fishvisuals.mixin;

import dev.latvian.mods.kubejs.client.GeneratedClientResourcePack;
import dev.latvian.mods.kubejs.script.data.GeneratedData;
import dev.sunlit.bettersunlit.client.KubeJsPackPerf;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collections;
import java.util.Map;

/**
 * Defers eager {@code GeneratedClientResourcePack.getGenerated()} during {@code Minecraft.<init>}
 * onto a background worker thread instead of blocking the Render thread.
 */
@Mixin(value = GeneratedClientResourcePack.class, remap = false)
public abstract class PerfKubeJsClientPackMixin {
    @Redirect(
            method = "<init>(Lnet/minecraft/client/Minecraft;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/latvian/mods/kubejs/client/GeneratedClientResourcePack;getGenerated()Ljava/util/Map;"))
    private Map<ResourceLocation, GeneratedData> sunlit$prefetchGeneratedAsync(GeneratedClientResourcePack pack) {
        KubeJsPackPerf.prefetchGeneratedAsync(pack);
        return Collections.emptyMap();
    }
}
