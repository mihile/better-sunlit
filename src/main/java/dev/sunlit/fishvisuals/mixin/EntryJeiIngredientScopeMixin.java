package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ScopedCandidateCache;
import mezz.jei.common.util.StackHelper;
import mezz.jei.library.plugins.vanilla.ingredients.ItemStackListFactory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.util.List;

@Pseudo
@Mixin(targets="mezz.jei.library.plugins.vanilla.VanillaPlugin",remap=false)
public abstract class EntryJeiIngredientScopeMixin {
    @Redirect(method="registerIngredients(Lmezz/jei/api/registration/IModIngredientRegistration;)V",at=@At(value="INVOKE",target="Lmezz/jei/library/plugins/vanilla/ingredients/ItemStackListFactory;create(Lmezz/jei/common/util/StackHelper;)Ljava/util/List;"),require=1,remap=false)
    private List<ItemStack> sunlit$ingredientBuildScope(StackHelper helper) {
        try(var scope=ScopedCandidateCache.open()){return ItemStackListFactory.create(helper);}
    }
}
