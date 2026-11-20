package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.ObjectHolderPerf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ObjectHolderRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Replaces {@code ObjectHolderRegistry}'s unindexed {@code HashSet} scan with registry-bucketed
 * lookup via {@link ObjectHolderPerf}.
 */
@Mixin(value = ObjectHolderRegistry.class, remap = false)
public abstract class PerfObjectHolderRegistryMixin {
    @Inject(method = "addHandler", at = @At("HEAD"), cancellable = true)
    private static void sunlit$addIndexedHandler(
            Consumer<Predicate<ResourceLocation>> consumer, CallbackInfo ci) {
        ObjectHolderPerf.addHandler(consumer);
        ci.cancel();
    }

    @Inject(method = "removeHandler", at = @At("HEAD"), cancellable = true)
    private static void sunlit$removeIndexedHandler(
            Consumer<Predicate<ResourceLocation>> consumer, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(ObjectHolderPerf.removeHandler(consumer));
    }

    @Inject(method = "applyObjectHolders(Ljava/util/function/Predicate;)V", at = @At("HEAD"), cancellable = true)
    private static void sunlit$applyIndexedHolders(
            Predicate<ResourceLocation> filter, CallbackInfo ci) {
        ObjectHolderPerf.applyObjectHolders(filter);
        ci.cancel();
    }
}
