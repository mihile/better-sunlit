package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.PuzzlesModelPerf;
import fuzs.puzzleslib.api.client.event.v1.ModelEvents;
import fuzs.puzzleslib.api.event.v1.core.ForgeEventInvokerRegistry;
import fuzs.puzzleslib.impl.client.event.ForgeClientEventInvokers;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BiConsumer;

@Mixin(value = ForgeClientEventInvokers.class, remap = false)
public abstract class PerfPuzzlesModelEventsMixin {
    @SuppressWarnings("unchecked")
    @Redirect(
            method = "registerLoadingHandlers",
            at = @At(
                    value = "INVOKE",
                    target = "Lfuzs/puzzleslib/api/event/v1/core/ForgeEventInvokerRegistry;register(Ljava/lang/Class;Ljava/lang/Class;Ljava/util/function/BiConsumer;)V"))
    private static <T, E extends Event> void sunlit$redirectModifyUnbakedModel(
            ForgeEventInvokerRegistry registry,
            Class<T> eventClass,
            Class<E> forgeEventClass,
            BiConsumer<T, E> converter) {
        if (eventClass == ModelEvents.ModifyUnbakedModel.class) {
            BiConsumer<ModelEvents.ModifyUnbakedModel, ModelEvent.ModifyBakingResult> original =
                    (BiConsumer<ModelEvents.ModifyUnbakedModel, ModelEvent.ModifyBakingResult>) (BiConsumer<?, ?>) converter;
            BiConsumer<ModelEvents.ModifyUnbakedModel, ModelEvent.ModifyBakingResult> fast =
                    (callback, evt) -> PuzzlesModelPerf.handleModifyUnbakedModel(callback, evt, original);
            registry.register(eventClass, forgeEventClass, (BiConsumer<T, E>) (BiConsumer<?, ?>) fast);
            return;
        }
        registry.register(eventClass, forgeEventClass, converter);
    }
}
