package dev.sunlit.bettersunlit.client;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.ItemEmiStack;
import net.minecraft.nbt.CompoundTag;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;

import java.util.List;
import java.util.function.Function;

public final class BackpackEmiLookup {
    private BackpackEmiLookup() {}

    public static EmiIngredient forDisplay(EmiIngredient ingredient, Function<EmiStack, List<EmiRecipe>> recipes) {
        if (!(ingredient instanceof ItemEmiStack stack) || !(stack.getKey() instanceof BackpackItem item)) {
            return ingredient;
        }
        CompoundTag data = stack.getNbt();
        if (data == null) return ingredient;

        boolean hasRenderData = data.m_128441_("renderInfo");
        boolean hasCustomColors = data.m_128441_("clothColor") || data.m_128441_("borderColor");
        if ((!hasRenderData && !hasCustomColors) || !recipes.apply(stack).isEmpty()) return ingredient;

        if (hasRenderData) {
            CompoundTag lookupData = data.m_6426_();
            lookupData.m_128473_("renderInfo");
            EmiStack sameColor = EmiStack.of(item, lookupData, stack.getAmount());
            if (!recipes.apply(sameColor).isEmpty()) return sameColor;
        }
        if (hasCustomColors) {
            // Arbitrary dyed colors may have no indexed example. Use this tier's default display.
            EmiStack base = EmiStack.of(item, stack.getAmount());
            if (!recipes.apply(base).isEmpty()) return base;
        }
        return ingredient;
    }
}
