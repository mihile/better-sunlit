package dev.sunlit.fishvisuals.mixin;

import com.google.common.collect.ImmutableMap;
import dev.sunlit.bettersunlit.client.LayerDefinitionPerf;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Evaluates registered {@code LayerDefinition} suppliers in parallel while feeding them into
 * Forge's {@code ImmutableMap.Builder} in original key order.
 */
@Mixin(value = ForgeHooksClient.class, remap = false)
public abstract class PerfForgeLayerDefinitionsMixin {
    @Shadow
    private static Map<ModelLayerLocation, Supplier<LayerDefinition>> layerDefinitions;

    @Inject(method = "loadLayerDefinitions", at = @At("HEAD"), cancellable = true)
    private static void sunlit$parallelLoadLayerDefinitions(
            ImmutableMap.Builder<ModelLayerLocation, LayerDefinition> builder,
            CallbackInfo ci) {
        ci.cancel();
        LayerDefinitionPerf.evaluateAndInsert(layerDefinitions, builder::put);
    }
}
