package dev.sunlit.bettersunlit.client;

import dev.emi.emi.api.recipe.EmiCraftingRecipe;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.ListEmiIngredient;
import dev.emi.emi.handler.CraftingRecipeHandler;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.sunlit.fishvisuals.mixin.EmiCraftingSpecAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.common.CraftingDisplaySpec;
import net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.emi.CraftingSpecEmiRecipe;
import net.p3pp3rf1y.sophisticatedstorage.item.ChestBlockItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EmiSpecCraftingHandler extends CraftingRecipeHandler {
    public static final EmiSpecCraftingHandler INSTANCE = new EmiSpecCraftingHandler();
    private static final int GRID_WIDTH = 3;
    private static final int GRID_SIZE = GRID_WIDTH * GRID_WIDTH;

    private EmiSpecCraftingHandler() {}

    @Override
    public boolean supportsRecipe(EmiRecipe recipe) {
        return originalRecipe(recipe, Minecraft.m_91087_().f_91073_) != null;
    }

    @Override
    public boolean canCraft(EmiRecipe recipe, EmiCraftContext<CraftingMenu> context) {
        return checkedTransfer(recipe, context) != null;
    }

    @Override
    public boolean craft(EmiRecipe recipe, EmiCraftContext<CraftingMenu> context) {
        EmiRecipe transfer = checkedTransfer(recipe, context);
        return transfer != null && super.craft(transfer, context);
    }

    private static CraftingRecipe originalRecipe(EmiRecipe recipe, ClientLevel level) {
        if (level == null || !(recipe instanceof EmiCraftingSpecAccessor accessor)) return null;
        CraftingDisplaySpec spec = accessor.sunlit$craftingSpec();
        if (spec.replacedRecipeIds().size() != 1) return null;
        ResourceLocation id = spec.replacedRecipeIds().iterator().next();
        Recipe<?> original = level.m_7465_().m_44043_(id).orElse(null);
        if (original instanceof ShapedRecipe shaped) {
            return shaped.m_44220_() <= GRID_WIDTH && shaped.m_44221_() <= GRID_WIDTH ? shaped : null;
        }
        if (original instanceof ShapelessRecipe shapeless) {
            return shapeless.m_7527_().size() <= GRID_SIZE ? shapeless : null;
        }
        return null;
    }

    private EmiRecipe checkedTransfer(EmiRecipe recipe, EmiCraftContext<CraftingMenu> context) {
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        CraftingRecipe original = originalRecipe(recipe, level);
        if (original == null || !(recipe instanceof CraftingSpecEmiRecipe display)) return null;

        CraftingMenu menu = context.getScreenHandler();
        List<ItemStack> inventory = getInputSources(menu).stream().map(Slot::m_7993_)
                .filter(stack -> !stack.m_41619_()).toList();
        List<EmiIngredient> inputs = completeInputs(original, display.getDisplayInputSlots(), inventory);
        ItemStack output = original.m_8043_(level.m_9598_());
        if (inputs == null || output.m_41619_()) return null;

        EmiRecipe transfer = new EmiCraftingRecipe(inputs, EmiStack.of(output), original.m_6423_(),
                original instanceof ShapelessRecipe);
        List<ItemStack> placement = EmiRecipeFiller.getStacks(this, transfer, context.getScreen(), context.getAmount());
        if (placement == null || placement.size() > GRID_SIZE) return null;

        // The native matcher checks additional constraints such as packed or double chests.
        NonNullList<ItemStack> grid = NonNullList.m_122780_(GRID_SIZE, ItemStack.f_41583_);
        for (int i = 0; i < placement.size(); i++) {
            ItemStack stack = placement.get(i);
            if (stack != null) grid.set(i, stack.m_41777_());
        }
        return original.m_5818_(new TransientCraftingContainer(menu, GRID_WIDTH, GRID_WIDTH, grid), level)
                ? transfer : null;
    }

    static List<EmiIngredient> completeInputs(CraftingRecipe original, List<List<ItemStack>> displayed,
            List<ItemStack> inventory) {
        List<Ingredient> required = original.m_7527_();
        if (required.isEmpty()) return null;
        boolean shaped = original instanceof ShapedRecipe;
        int width = shaped ? ((ShapedRecipe) original).m_44220_() : GRID_WIDTH;
        if (width < 1 || width > GRID_WIDTH || required.size() > GRID_SIZE) return null;

        List<EmiIngredient> inputs = new ArrayList<>(Collections.nCopies(GRID_SIZE, EmiStack.EMPTY));
        boolean storage = original.m_6423_().m_135827_().equals("sophisticatedstorage");
        for (int i = 0; i < required.size(); i++) {
            Ingredient ingredient = required.get(i);
            if (ingredient.m_43947_()) continue;
            List<ItemStack> examples = i < displayed.size() ? displayed.get(i) : List.of();
            List<EmiStack> available = inventory.stream().filter(ingredient)
                    .filter(stack -> !storage || matchesChestForm(stack, examples))
                    .map(stack -> EmiStack.of(stack, 1))
                    .distinct().toList();
            if (available.isEmpty()) return null;
            inputs.set(i / width * GRID_WIDTH + i % width, available.size() == 1
                    ? available.get(0) : new ListEmiIngredient(available, 1));
        }
        return inputs;
    }

    private static boolean matchesChestForm(ItemStack stack, List<ItemStack> examples) {
        if (!(stack.m_41720_() instanceof ChestBlockItem)) return true;
        boolean doubled = ChestBlockItem.isDoubleChest(stack);
        boolean hadChest = false;
        for (ItemStack example : examples) {
            if (example.m_41720_() != stack.m_41720_()) continue;
            hadChest = true;
            if (ChestBlockItem.isDoubleChest(example) == doubled) return true;
        }
        return !hadChest;
    }
}
