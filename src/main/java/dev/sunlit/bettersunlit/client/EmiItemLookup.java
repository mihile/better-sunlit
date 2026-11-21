package dev.sunlit.bettersunlit.client;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.ItemEmiStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import net.p3pp3rf1y.sophisticatedstorage.item.StorageBlockItem;

import java.util.List;
import java.util.function.Function;

public final class EmiItemLookup {
    private EmiItemLookup() {}

    public static EmiIngredient forDisplay(EmiIngredient ingredient, Function<EmiStack, List<EmiRecipe>> recipes) {
        if (!(ingredient instanceof ItemEmiStack stack) || !(stack.getKey() instanceof Item item)) {
            return ingredient;
        }
        CompoundTag data = stack.getNbt();
        if (data == null) return ingredient;

        ResourceLocation id = stack.getId();
        return switch (id.m_135827_()) {
            case "sophisticatedbackpacks" -> item instanceof BackpackItem
                    ? backpack(stack, item, data, recipes) : stack;
            case "sophisticatedstorage" -> item instanceof StorageBlockItem
                    ? without(stack, item, data, recipes, "mainColor", "accentColor") : stack;
            case "botania" -> id.m_135815_().equals("brew_flask")
                    ? without(stack, item, data, recipes, "swigsLeft") : stack;
            case "supplementaries" -> id.m_135815_().equals("bamboo_spikes_tipped")
                    ? without(stack, item, data, recipes, "Damage") : stack;
            default -> stack;
        };
    }

    private static EmiStack backpack(EmiStack stack, Item item, CompoundTag data,
            Function<EmiStack, List<EmiRecipe>> recipes) {
        boolean hasRenderData = data.m_128441_("renderInfo");
        boolean hasCustomColors = data.m_128441_("clothColor") || data.m_128441_("borderColor");
        if ((!hasRenderData && !hasCustomColors) || !recipes.apply(stack).isEmpty()) return stack;

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
        return stack;
    }

    private static EmiStack without(EmiStack stack, Item item, CompoundTag data,
            Function<EmiStack, List<EmiRecipe>> recipes, String... keys) {
        CompoundTag lookupData = null;
        for (String key : keys) {
            if (!data.m_128441_(key)) continue;
            if (lookupData == null) {
                if (!recipes.apply(stack).isEmpty()) return stack;
                lookupData = data.m_6426_();
            }
            lookupData.m_128473_(key);
        }
        if (lookupData == null) return stack;
        EmiStack lookup = EmiStack.of(item, lookupData.m_128456_() ? null : lookupData, stack.getAmount());
        return recipes.apply(lookup).isEmpty() ? stack : lookup;
    }
}
