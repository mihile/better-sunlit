package dev.sunlit.fishvisuals.mixin;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.sunlit.bettersunlit.client.EmiSpecCraftingHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.CraftingMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.emi.emi.registry.EmiRecipeFiller", remap = false)
public abstract class EmiSpecRecipeFillerMixin {
    @Inject(method = "isSupported", at = @At("HEAD"), cancellable = true)
    private static void sunlit$showTransferForCraftingSpec(EmiRecipe recipe, CallbackInfoReturnable<Boolean> result) {
        if (EmiSpecCraftingHandler.INSTANCE.supportsRecipe(recipe)) result.setReturnValue(true);
    }

    @Inject(method = "getFirstValidHandler", at = @At("HEAD"), cancellable = true)
    private static void sunlit$transferCraftingSpec(EmiRecipe recipe, AbstractContainerScreen<?> screen,
            CallbackInfoReturnable<EmiRecipeHandler<?>> result) {
        if (screen != null && screen.m_6262_() instanceof CraftingMenu
                && EmiSpecCraftingHandler.INSTANCE.supportsRecipe(recipe)) {
            result.setReturnValue(EmiSpecCraftingHandler.INSTANCE);
        }
    }
}
