package dev.sunlit.fishvisuals.mixin;
import dev.sunlit.bettersunlit.client.JeiPlainUid;
import mezz.jei.api.ingredients.subtypes.IIngredientSubtypeInterpreter;
import org.spongepowered.asm.mixin.*;
import java.util.Map;

@Pseudo
@Mixin(targets="mezz.jei.library.ingredients.subtypes.SubtypeInterpreters",remap=false)
public abstract class EntryJeiSubtypeInterpretersMixin implements JeiPlainUid.Interpreters {
    @Shadow @Final private Map<Object,IIngredientSubtypeInterpreter<?>> map;
    @Override public boolean sunlit$containsBase(Object item) { return map.containsKey(item); }
}
