package dev.sunlit.fishvisuals.mixin;

import com.simibubi.create.compat.jei.category.BlockCuttingCategory;
import com.simibubi.create.compat.jei.category.BlockCuttingCategory.CondensedBlockCuttingRecipe;
import dev.sunlit.bettersunlit.client.CreateIngredientGrouping;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Pseudo
@Mixin(value = BlockCuttingCategory.class, remap = false)
public abstract class PerfCreateBlockCuttingMixin {
    @Inject(method = "condenseRecipes", at = @At("HEAD"), cancellable = true, require = 1)
    private static void sunlit$reuseGroupIngredients(List<Recipe<?>> recipes,
            CallbackInfoReturnable<List<CondensedBlockCuttingRecipe>> cir) {
        cir.setReturnValue(CreateIngredientGrouping.group(recipes));
    }
}
