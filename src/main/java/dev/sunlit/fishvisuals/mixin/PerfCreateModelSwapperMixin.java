package dev.sunlit.fishvisuals.mixin;

import com.simibubi.create.foundation.block.render.CustomBlockModels;
import com.simibubi.create.foundation.item.render.CustomItemModels;
import com.simibubi.create.foundation.model.ModelSwapper;
import dev.sunlit.bettersunlit.client.CreateModelSwapPerf;
import net.minecraftforge.client.event.ModelEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces Create's sequential per-state {@code ModelLocationBuilder} and per-entry {@code put}
 * calls with parallel key construction and a single batched {@code models.putAll}.
 */
@Mixin(value = ModelSwapper.class, remap = false)
public abstract class PerfCreateModelSwapperMixin {
    @Shadow
    protected CustomBlockModels customBlockModels;

    @Shadow
    protected CustomItemModels customItemModels;

    @Inject(method = "onModelBake", at = @At("HEAD"), cancellable = true)
    private void sunlit$fastOnModelBake(ModelEvent.ModifyBakingResult event, CallbackInfo ci) {
        CreateModelSwapPerf.fastOnModelBake(event, customBlockModels, customItemModels);
        ci.cancel();
    }
}
