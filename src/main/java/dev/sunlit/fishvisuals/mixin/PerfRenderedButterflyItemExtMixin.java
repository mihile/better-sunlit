package dev.sunlit.fishvisuals.mixin;

import com.cicada.longwings.client.renderers.ButterflyItemRenderer;
import dev.sunlit.bettersunlit.client.LongwingsClientPerf;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.cicada.longwings.block.RenderedItems$RenderedButterflyItem$1", remap = false)
public class PerfRenderedButterflyItemExtMixin {
    @Inject(
            method = "getCustomRenderer()Lnet/minecraft/client/renderer/BlockEntityWithoutLevelRenderer;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void sunlit$cacheButterflyCustomRenderer(CallbackInfoReturnable<BlockEntityWithoutLevelRenderer> cir) {
        cir.setReturnValue(LongwingsClientPerf.getOrCreateButterflyRenderer(ButterflyItemRenderer::new));
    }
}
