package dev.sunlit.fishvisuals.mixin;

import de.keksuccino.fancymenu.customization.panorama.LocalTexturePanoramaRenderer;
import de.keksuccino.fancymenu.util.ScreenUtils;
import de.keksuccino.fancymenu.util.resource.ResourceSupplier;
import de.keksuccino.fancymenu.util.resource.resources.texture.ITexture;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = LocalTexturePanoramaRenderer.class, remap = false)
public abstract class FancyMenuPanoramaMixin {
    @Shadow
    @Final
    public List<ResourceSupplier<ITexture>> panoramaImageSuppliers;

    @Inject(method = "renderRaw", at = @At("HEAD"), cancellable = true)
    private void sunlit$deferPendingPanorama(GuiGraphics graphics, float opacity, float partialTick,
            CallbackInfo callback) {
        if (sunlit$hasPendingFaces(panoramaImageSuppliers)) {
            // Match the Drippy loading screen while FancyMenu decodes the panorama.
            graphics.m_280509_(0, 0, ScreenUtils.getScreenWidth(), ScreenUtils.getScreenHeight(), 0xFF547E64);
            callback.cancel();
        }
    }

    @Unique
    private static boolean sunlit$hasPendingFaces(List<ResourceSupplier<ITexture>> images) {
        boolean pending = false;
        int faceCount = Math.min(images.size(), 6);
        for (int face = 0; face < faceCount; face++) {
            // Request every face now so FancyMenu can decode them in parallel.
            ITexture texture = images.get(face).get();
            pending |= texture != null && !texture.isReady() && !texture.isLoadingFailed();
        }
        return pending;
    }
}
