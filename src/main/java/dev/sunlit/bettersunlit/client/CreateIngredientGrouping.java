package dev.sunlit.bettersunlit.client;

import com.simibubi.create.compat.jei.category.BlockCuttingCategory.CondensedBlockCuttingRecipe;
import com.simibubi.create.compat.jei.category.CreateRecipeCategory;
import com.simibubi.create.foundation.item.ItemHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import java.util.*;
import java.util.function.Function;

/** Invocation-local prefilter for reviewed vanilla stonecutting inputs. Never replaces Create's equality test. */
public final class CreateIngredientGrouping {
    private record Signature(int size, Item firstItem) {
        @Override public int hashCode() { return 31 * size + System.identityHashCode(firstItem); }
        @Override public boolean equals(Object value) {
            return value instanceof Signature other && size == other.size && firstItem == other.firstItem;
        }
    }
    private CreateIngredientGrouping() {}

    public static List<CondensedBlockCuttingRecipe> group(List<Recipe<?>> recipes) {
        Function<Recipe<?>,Ingredient> extract = recipe -> recipe.m_7527_().get(0);
        // Unknown recipe getters may have side effects or mutate ingredients: keep their original traversal.
        for (Recipe<?> recipe : recipes) if (recipe.getClass() != StonecutterRecipe.class) return baseline(recipes, extract);
        IdentityHashMap<Recipe<?>,Ingredient> inputs = new IdentityHashMap<>();
        for (Recipe<?> recipe : recipes) {
            Ingredient ingredient = extract.apply(recipe);
            inputs.put(recipe, ingredient);
        }
        return RecipeGrouping.groupKeyed(recipes, inputs::get, CreateIngredientGrouping::signature,
                ItemHelper::matchIngredients, CondensedBlockCuttingRecipe::new,
                (group, recipe) -> group.addOutput(CreateRecipeCategory.getResultItem(recipe)));
    }
    private static Signature signature(Ingredient ingredient) {
        if (ingredient.getClass() != Ingredient.class) return null;
        ItemStack[] stacks = ingredient.m_43908_();
        Item first = stacks.length == 0 || stacks[0].m_41619_() ? null : stacks[0].m_41720_();
        return new Signature(stacks.length, first);
    }
    private static List<CondensedBlockCuttingRecipe> baseline(List<Recipe<?>> recipes, Function<Recipe<?>,Ingredient> ingredient) {
        return RecipeGrouping.group(recipes, ingredient, ItemHelper::matchIngredients,
                CondensedBlockCuttingRecipe::new,
                (group, recipe) -> group.addOutput(CreateRecipeCategory.getResultItem(recipe)));
    }
}
