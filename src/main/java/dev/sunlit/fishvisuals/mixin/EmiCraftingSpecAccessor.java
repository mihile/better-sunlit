package dev.sunlit.fishvisuals.mixin;

import net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.common.CraftingDisplaySpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.emi.CraftingSpecEmiRecipe", remap = false)
public interface EmiCraftingSpecAccessor {
    @Accessor("spec")
    CraftingDisplaySpec sunlit$craftingSpec();
}
