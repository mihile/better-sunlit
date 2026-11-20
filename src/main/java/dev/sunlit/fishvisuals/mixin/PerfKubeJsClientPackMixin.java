package dev.sunlit.fishvisuals.mixin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.latvian.mods.kubejs.client.GeneratedClientResourcePack;
import dev.latvian.mods.kubejs.generator.AssetJsonGenerator;
import dev.latvian.mods.kubejs.script.data.GeneratedData;
import dev.sunlit.bettersunlit.client.KubeJsPackPerf;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * Defers eager {@code GeneratedClientResourcePack.getGenerated()} during {@code Minecraft.<init>}
 * onto a background worker thread and bypasses serial disk reads and redundant JSON roundtrips
 * for generated KubeJS language assets.
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

    @Redirect(
            method = "generate(Ljava/util/Map;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/nio/file/Files;newBufferedReader(Ljava/nio/file/Path;)Ljava/io/BufferedReader;"))
    private BufferedReader sunlit$openPreloadedLangReader(Path file) throws IOException {
        return KubeJsPackPerf.openLangBufferedReader(file);
    }

    @Redirect(
            method = "generate(Ljava/util/Map;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/gson/Gson;fromJson(Ljava/io/Reader;Ljava/lang/Class;)Ljava/lang/Object;"))
    private <T> T sunlit$parsePreloadedLangJson(Gson gson, Reader reader, Class<T> type) {
        return KubeJsPackPerf.parseLangJson(gson, reader, type);
    }

    @Redirect(
            method = "generate(Ljava/util/Map;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/latvian/mods/kubejs/generator/AssetJsonGenerator;json(Lnet/minecraft/resources/ResourceLocation;Lcom/google/gson/JsonElement;)V"))
    private void sunlit$registerGeneratedLangJson(
            AssetJsonGenerator generator, ResourceLocation id, JsonElement json) {
        KubeJsPackPerf.registerGeneratedLangJson(generator, id, json);
    }
}
