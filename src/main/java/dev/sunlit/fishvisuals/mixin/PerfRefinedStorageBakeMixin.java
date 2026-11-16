package dev.sunlit.fishvisuals.mixin;

import com.refinedmods.refinedstorage.render.BakedModelOverrideRegistry;
import com.refinedmods.refinedstorage.setup.ClientSetup;
import dev.sunlit.bettersunlit.client.RefinedStorageBakePerf;
import net.minecraftforge.client.event.ModelEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces Refined Storage's allocation-heavy full-registry model loop with a namespace-indexed
 * lookup.
 */
@Mixin(value = ClientSetup.class, remap = false)
public abstract class PerfRefinedStorageBakeMixin {
    @Shadow
    @Final
    private static BakedModelOverrideRegistry BAKED_MODEL_OVERRIDE_REGISTRY;

    @Inject(method = "onModelBake", at = @At("HEAD"), cancellable = true)
    private static void sunlit$fastOnModelBake(ModelEvent.ModifyBakingResult event, CallbackInfo ci) {
        if (RefinedStorageBakePerf.tryApplyOverrides(event, BAKED_MODEL_OVERRIDE_REGISTRY)) {
            ci.cancel();
        }
    }
}
