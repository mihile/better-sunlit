package dev.sunlit.fishvisuals.mixin;
import dev.sunlit.bettersunlit.client.JeiPlainUid;
import mezz.jei.api.ingredients.subtypes.ISubtypeManager;
import org.spongepowered.asm.mixin.*;

@Pseudo
@Mixin(targets="mezz.jei.library.plugins.vanilla.ingredients.ItemStackHelper",remap=false)
public abstract class EntryJeiItemHelperMixin implements JeiPlainUid.Helper {
    @Shadow @Final private ISubtypeManager subtypeManager;
    @Override public ISubtypeManager sunlit$subtypeManager() { return subtypeManager; }
}
