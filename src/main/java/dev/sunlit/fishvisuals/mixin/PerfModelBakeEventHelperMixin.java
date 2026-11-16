package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ModelBakePerf;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.resources.ResourceLocation;
import org.embeddedt.modernfix.forge.dynresources.ModelBakeEventHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Replaces redundant registry-wide iteration in {@code ModelBakeEventHelper.<init>} with clones of
 * the precomputed fastutil sets when available.
 */
@Mixin(value = ModelBakeEventHelper.class, remap = false)
public abstract class PerfModelBakeEventHelperMixin {
    @Shadow
    @Final
    @Mutable
    private Set<ResourceLocation> topLevelModelLocations;

    @Shadow
    @Final
    @Mutable
    private Set<String> namespacesWithModels;

    @Redirect(
            method = "<init>(Ljava/util/Map;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/DefaultedRegistry;iterator()Ljava/util/Iterator;"))
    private Iterator<?> sunlit$skipStateCountIteration(DefaultedRegistry<?> registry) {
        if (ModelBakePerf.beginIfReady()) {
            return Collections.emptyIterator();
        }
        return registry.iterator();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(
            method = "<init>(Ljava/util/Map;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Set;forEach(Ljava/util/function/Consumer;)V",
                    ordinal = 0))
    private void sunlit$applyPrewarmedBlocks(Set set, Consumer action) {
        ModelBakePerf.PrewarmData prewarm = ModelBakePerf.getActivePrewarm();
        if (prewarm != null) {
            this.topLevelModelLocations = prewarm.topLevelModelLocations.clone();
            this.namespacesWithModels = prewarm.namespacesWithModels.clone();
            return;
        }
        set.forEach(action);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(
            method = "<init>(Ljava/util/Map;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Set;forEach(Ljava/util/function/Consumer;)V",
                    ordinal = 1))
    private void sunlit$skipPrewarmedItems(Set set, Consumer action) {
        if (ModelBakePerf.getActivePrewarm() != null) {
            return;
        }
        set.forEach(action);
    }

    @Inject(method = "<init>(Ljava/util/Map;)V", at = @At("RETURN"))
    private void sunlit$clearPrewarmThreadLocal(CallbackInfo ci) {
        ModelBakePerf.finishApply();
    }
}
