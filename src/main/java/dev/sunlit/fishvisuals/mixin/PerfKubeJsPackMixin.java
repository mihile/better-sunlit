package dev.sunlit.fishvisuals.mixin;

import dev.latvian.mods.kubejs.script.data.GeneratedData;
import dev.latvian.mods.kubejs.script.data.GeneratedResourcePack;
import dev.sunlit.bettersunlit.client.KubeJsPackPerf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Mixin(value = GeneratedResourcePack.class, remap = false)
public abstract class PerfKubeJsPackMixin {
    @Shadow
    @Final
    private PackType packType;

    @Shadow
    private Map<ResourceLocation, GeneratedData> generated;

    @Unique
    private volatile Map<ResourceLocation, GeneratedData> sunlit$readyGenerated;

    @Shadow
    public abstract void generate(Map<ResourceLocation, GeneratedData> map);

    @Shadow
    protected abstract boolean forgetFile(String path);

    @Shadow
    protected abstract boolean skipFile(GeneratedData data);

    @Shadow
    public abstract Map<ResourceLocation, GeneratedData> getGenerated();

    @Inject(method = "getGenerated()Ljava/util/Map;", at = @At("HEAD"), cancellable = true)
    private void sunlit$fastGetGenerated(CallbackInfoReturnable<Map<ResourceLocation, GeneratedData>> cir) {
        KubeJsPackPerf.awaitPrefetch((GeneratedResourcePack) (Object) this);
        if (this.generated == null) {
            Map<ResourceLocation, GeneratedData> map = new HashMap<>();
            this.generated = map;
            this.generate(map);
            KubeJsPackPerf.populateGeneratedFromDisk(
                    this.packType,
                    map,
                    this::forgetFile,
                    this::skipFile);
            map.put(GeneratedData.INTERNAL_RELOAD.id(), GeneratedData.INTERNAL_RELOAD);
            Map<ResourceLocation, GeneratedData> immutable = Map.copyOf(map);
            this.generated = immutable;
            this.sunlit$readyGenerated = immutable;
        } else if (this.sunlit$readyGenerated != this.generated) {
            this.sunlit$readyGenerated = this.generated;
        }
        cir.setReturnValue(this.generated);
    }

    @Inject(method = "scanForInvalidFiles", at = @At("HEAD"), cancellable = true)
    private static void sunlit$fastScanForInvalidFiles(String pathName, Path path, CallbackInfo ci) throws IOException {
        ci.cancel();
        KubeJsPackPerf.fastScanForInvalidFilesAsync(pathName, path);
    }

    @Inject(method = "m_8031_", at = @At("HEAD"), cancellable = true)
    private void sunlit$fastListResources(
            PackType type,
            String namespace,
            String path,
            PackResources.ResourceOutput output,
            CallbackInfo ci) {
        ci.cancel();
        if (type != this.packType) {
            return;
        }
        Map<ResourceLocation, GeneratedData> map = this.sunlit$readyGenerated;
        if (map == null || map != this.generated) {
            synchronized (this) {
                map = this.getGenerated();
                this.sunlit$readyGenerated = map;
            }
        }
        KubeJsPackPerf.listResources(map, namespace, path, output);
    }
}
