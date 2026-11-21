package dev.sunlit.fishvisuals.mixin;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.sunlit.bettersunlit.client.BackpackEmiLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "dev.emi.emi.screen.EmiScreenManager", remap = false)
public abstract class BackpackEmiLookupMixin {
    @ModifyArg(method = "stackInteraction(Ldev/emi/emi/api/stack/EmiStackInteraction;Ljava/util/function/Function;)Z",
            at = @At(value = "INVOKE", target = "Ldev/emi/emi/api/EmiApi;displayUses(Ldev/emi/emi/api/stack/EmiIngredient;)V"),
            index = 0, require = 1)
    private static EmiIngredient sunlit$findBackpackUses(EmiIngredient ingredient) {
        return BackpackEmiLookup.forDisplay(ingredient, EmiApi.getRecipeManager()::getRecipesByInput);
    }

    @ModifyArg(method = "stackInteraction(Ldev/emi/emi/api/stack/EmiStackInteraction;Ljava/util/function/Function;)Z",
            at = @At(value = "INVOKE", target = "Ldev/emi/emi/api/EmiApi;displayRecipes(Ldev/emi/emi/api/stack/EmiIngredient;)V"),
            index = 0, require = 1)
    private static EmiIngredient sunlit$findBackpackRecipes(EmiIngredient ingredient) {
        return BackpackEmiLookup.forDisplay(ingredient, EmiApi.getRecipeManager()::getRecipesByOutput);
    }
}
