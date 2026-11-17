package dev.sunlit.fishvisuals.mixin;
import dev.sunlit.bettersunlit.client.JeiPlainUid;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="mezz.jei.library.recipes.collect.RecipeMap",remap=false)
public abstract class EntryJeiRecipeUidMixin {
    @Shadow @Final private IIngredientManager ingredientManager;
    @Inject(method="getIngredientUid(Lmezz/jei/api/ingredients/ITypedIngredient;)Ljava/lang/Object;",at=@At("HEAD"),cancellable=true)
    private <T> void sunlit$plainItemUid(ITypedIngredient<T> ingredient, CallbackInfoReturnable<Object> cir) {
        Object uid=JeiPlainUid.tryUid(ingredient,ingredientManager);
        if(uid!=null) cir.setReturnValue(uid);
    }
}
