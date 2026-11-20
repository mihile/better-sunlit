package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.QuickPackPerf;
import me.drex.quickpack.QuickPack;
import me.drex.quickpack.duck.IFilePackResources;
import net.minecraft.server.packs.PackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.zip.ZipFile;

/**
 * Replaces QuickPack's regex {@code String.split("/")} file-tree indexing with a cached,
 * zero-regex parallel pre-scanned implementation for single-pack {@link IFilePackResources}.
 */
@Mixin(value = QuickPack.class, remap = false)
public abstract class PerfQuickPackMixin {
    @Inject(method = "initializeFileTrees", at = @At("HEAD"), cancellable = true)
    private static void sunlit$fastInitializeFileTrees(ZipFile zipFile, List<PackResources> packs, CallbackInfo ci) {
        if (zipFile == null || packs == null || packs.size() != 1) {
            return;
        }
        PackResources single = packs.get(0);
        if (!(single instanceof IFilePackResources target)) {
            return;
        }
        QuickPackPerf.PrebuiltTree prebuilt = QuickPackPerf.getOrAwaitPrebuilt(zipFile);
        if (prebuilt != null) {
            target.quick_pack$initializeFileTree(prebuilt.fileTree, prebuilt.namespaces);
            ci.cancel();
        }
    }
}
